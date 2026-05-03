(function () {
    'use strict';

    const userId = window.USER_ID || 'guest';
    const convId = window.CONV_ID || '';
    const agentName = window.AGENT_NAME || 'Assistant';

    const chatArea = document.getElementById('chatArea');
    const emptyHint = document.getElementById('emptyHint');
    const input = document.getElementById('input');
    const sendBtn = document.getElementById('sendBtn');
    const resetBtn = document.getElementById('resetBtn');
    const renameBtn = document.getElementById('renameBtn');
    const compactBtn = document.getElementById('compactBtn');
    const newConvBtn = document.getElementById('newConvBtn');
    const statusDot = document.getElementById('statusDot');
    const statusText = document.getElementById('statusText');
    const convList = document.getElementById('convList');
    const convTitle = document.getElementById('convTitle');
    const modelTag = document.getElementById('modelTag');
    const providerModal = document.getElementById('providerModal');
    const providerListEl = document.getElementById('providerList');
    const modalCancel = document.getElementById('modalCancel');
    const modalConfirm = document.getElementById('modalConfirm');

    let isBusy = false;
    let currentEs = null;
    /** 后端启用的 providers 元信息：[{id, displayName, modelName}, ...] + defaultId */
    let providersInfo = { providers: [], defaultId: null };
    /** 当前弹窗选中的 provider id */
    let modalSelectedProvider = null;
    /**
     * 当前会话尚未应答的 pending 工具：toolCallId -> {name, input}
     *
     * 流式 'tool_call' 事件入 map；非 pending 的 'tool_result' 事件出 map；
     * pending 的 'tool_result' 触发 HITL 审批 UI 渲染但保留在 map 里直到用户回填。
     * loadHistory 时也会扫描末尾 TOOL 消息回填本 map，让刷新页面后审批 UI 仍可恢复。
     */
    const pendingTools = new Map();
    /** ask_user 工具名常量；与后端 UserInteractionTool.TOOL_NAME 一致 */
    const ASK_USER = 'ask_user';

    // 两套完整 SVG —— 整段替换，不再依赖原始元素结构，避免 line/polygon vs path 之类的元素不匹配
    const SVG_SEND = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>';
    const SVG_STOP = '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="none"><rect x="6" y="6" width="12" height="12" rx="1.5"/></svg>';

    function setSendButtonMode(mode) {
        // mode: 'send' | 'stop'
        const isStop = mode === 'stop';
        sendBtn.classList.toggle('stop', isStop);
        sendBtn.title = isStop ? '中断生成' : '发送 (Enter)';
        sendBtn.setAttribute('aria-label', isStop ? '中断生成' : '发送');
        sendBtn.innerHTML = isStop ? SVG_STOP : SVG_SEND;
    }

    // ---------- DOM helpers ----------
    function el(tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }
    function avatarText(role) {
        if (role === 'USER') return userId.slice(0, 2).toUpperCase();
        if (role === 'TOOL') return 'T';
        return agentName.slice(0, 2).toUpperCase();
    }
    function svgIcon(d, size) {
        const NS = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(NS, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('width', size); svg.setAttribute('height', size);
        svg.setAttribute('fill', 'none'); svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2'); svg.setAttribute('stroke-linecap', 'round'); svg.setAttribute('stroke-linejoin', 'round');
        const p = document.createElementNS(NS, 'path');
        p.setAttribute('d', d);
        svg.appendChild(p);
        return svg;
    }
    function buildMeta(meta) {
        const wrap = el('div', 'msg-meta');
        if (meta.in != null) wrap.appendChild(el('span', 'pill', `in: ${meta.in}`));
        if (meta.out != null) wrap.appendChild(el('span', 'pill', `out: ${meta.out}`));
        if (meta.cached != null && meta.cached > 0) {
            const rate = (meta.hitRate * 100).toFixed(1);
            wrap.appendChild(el('span', 'pill cache-good', `cache: ${meta.cached} (${rate}%)`));
        }
        return wrap;
    }

    /**
     * 把 markdown 文本渲染为安全的 HTML。
     * - marked 用 GFM（GitHub 风味）+ breaks（单换行也变 <br>）
     * - DOMPurify 兜底防 XSS（模型输出可能含 <script> 之类）
     * - 链接全部 target=_blank + rel=noopener，避免恶意页面拿到 window.opener
     * - 库还没加载时 fallback 为纯文本（防止刚打开页面就发消息时报错）
     */
    function renderMarkdown(text) {
        if (!window.marked || !window.DOMPurify) {
            return text.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'})[c]);
        }
        const html = window.marked.parse(text || '', { gfm: true, breaks: true });
        const clean = window.DOMPurify.sanitize(html, {
            ADD_ATTR: ['target', 'rel']
        });
        // 给所有 <a> 加上 target/rel
        const tmp = document.createElement('div');
        tmp.innerHTML = clean;
        tmp.querySelectorAll('a').forEach(a => {
            a.setAttribute('target', '_blank');
            a.setAttribute('rel', 'noopener noreferrer');
        });
        return tmp.innerHTML;
    }
    function setStatus(state) {
        statusDot.className = 'status-dot' + (state === 'busy' ? ' busy' : state === 'error' ? ' error' : '');
        statusText.textContent = state === 'busy' ? '生成中...' : state === 'error' ? '出错' : '就绪';
    }
    function clearEmptyHint() {
        if (emptyHint && emptyHint.parentNode) emptyHint.remove();
    }
    function relativeTime(ts) {
        const diff = (Date.now() - ts) / 1000;
        if (diff < 60) return '刚刚';
        if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
        if (diff < 86400) return Math.floor(diff / 3600) + '小时前';
        return Math.floor(diff / 86400) + '天前';
    }

    // ---------- 渲染消息 ----------
    function appendUserMessage(text) {
        clearEmptyHint();
        const msg = el('div', 'msg user');
        msg.appendChild(el('div', 'avatar', avatarText('USER')));
        const wrap = el('div', 'bubble-wrap');
        wrap.appendChild(el('div', 'bubble', text));
        msg.appendChild(wrap);
        chatArea.appendChild(msg);
        chatArea.scrollTop = chatArea.scrollHeight;
    }
    function startAssistantBubble() {
        clearEmptyHint();
        const msg = el('div', 'msg assistant');
        msg.appendChild(el('div', 'avatar', avatarText('ASSISTANT')));
        const wrap = el('div', 'bubble-wrap');
        const bubble = el('div', 'bubble streaming');
        wrap.appendChild(bubble);
        msg.appendChild(wrap);
        chatArea.appendChild(msg);
        chatArea.scrollTop = chatArea.scrollHeight;
        return { bubble, wrap };
    }
    function startThinkingBubble() {
        clearEmptyHint();
        const msg = el('div', 'msg assistant');
        msg.appendChild(el('div', 'avatar', avatarText('ASSISTANT')));
        const wrap = el('div', 'bubble-wrap');
        const card = el('details', 'thinking-card');
        const summary = el('summary', 'thinking-summary');
        summary.appendChild(svgIcon('M9 21V9.5a2.5 2.5 0 015 0V18M9 12h5', 12));
        summary.appendChild(el('span', null, '思考中...'));
        const body = el('div', 'thinking-body');
        card.appendChild(summary);
        card.appendChild(body);
        wrap.appendChild(card);
        msg.appendChild(wrap);
        chatArea.appendChild(msg);
        chatArea.scrollTop = chatArea.scrollHeight;
        return { body, summary };
    }
    function appendToolCall(t) {
        clearEmptyHint();
        const msg = el('div', 'msg assistant');
        msg.appendChild(el('div', 'avatar', avatarText('ASSISTANT')));
        const wrap = el('div', 'bubble-wrap');
        const card = el('div', 'tool-step tool-call');
        // 标记 toolCallId，便于 done 阶段对"调用了但没渲染 HITL 卡片"的工具做 DOM 级兜底
        if (t.id) {
            card.dataset.toolCallId = t.id;
            card.dataset.toolName = t.name || '';
        }
        const head = el('div', 'tool-step-head');
        head.appendChild(svgIcon('M14 7l5 5-5 5M5 12h14', 14));
        head.appendChild(el('span', 'tool-name', '调用 ' + t.name));
        card.appendChild(head);
        card.appendChild(el('pre', 'tool-args', JSON.stringify(t.input, null, 2)));
        wrap.appendChild(card);
        msg.appendChild(wrap);
        chatArea.appendChild(msg);
        chatArea.scrollTop = chatArea.scrollHeight;
    }
    function appendToolResult(r) {
        const msg = el('div', 'msg tool');
        msg.appendChild(el('div', 'avatar', avatarText('TOOL')));
        const wrap = el('div', 'bubble-wrap');
        const card = el('div', 'tool-step tool-result' + (r.isError ? ' error' : '') + (r.pending ? ' pending' : ''));
        const head = el('div', 'tool-step-head');
        head.appendChild(svgIcon(r.isError ? 'M12 8v4M12 16h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' : 'M5 13l4 4L19 7', 14));
        head.appendChild(el('span', 'tool-name', r.pending ? '待人工审批' : (r.isError ? '工具错误' : '工具结果')));
        card.appendChild(head);
        card.appendChild(el('pre', 'tool-args', r.output));
        wrap.appendChild(card);
        msg.appendChild(wrap);
        chatArea.appendChild(msg);
        chatArea.scrollTop = chatArea.scrollHeight;
    }
    function appendApiMessageStatic(m) {
        if (m.role === 'SYSTEM') return;
        // assistant 的 toolUses 入 pendingTools map；如果后续在历史里出现对应非 pending result 会被 remove
        (m.toolUses || []).forEach(t => pendingTools.set(t.id, { name: t.name, input: t.input || {} }));
        if ((m.toolResults || []).length > 0) {
            m.toolResults.forEach(r => {
                if (r.pending) {
                    // 历史里残留的 pending → 直接渲染 HITL 卡片，让用户接着填
                    renderHitlPrompt(r.id);
                } else {
                    appendToolResult(r);
                    pendingTools.delete(r.id);
                }
            });
            return;
        }
        if ((m.toolUses || []).length > 0 && !m.text) { m.toolUses.forEach(appendToolCall); return; }
        if ((m.toolUses || []).length > 0 && m.text) {
            const { bubble } = startAssistantBubble();
            bubble.classList.add('markdown');
            bubble.innerHTML = renderMarkdown(m.text);
            bubble.classList.remove('streaming');
            m.toolUses.forEach(appendToolCall);
            return;
        }
        if (m.role === 'USER') appendUserMessage(m.text);
        else {
            const { bubble } = startAssistantBubble();
            bubble.classList.add('markdown');
            bubble.innerHTML = renderMarkdown(m.text);
            bubble.classList.remove('streaming');
        }
    }

    // ---------- HITL：人在回路 ----------

    /**
     * 渲染一个待回填的 pending 工具块。根据工具名分流：
     *   - ask_user → 按 input.ui_type 渲染输入控件
     *   - 其它 → 渲染"批准 / 拒绝"按钮（拒绝可填理由）
     *
     * 提交后 UI 会就地切换为"已处理"占位，不会重复触发。
     */
    function renderHitlPrompt(toolCallId) {
        const meta = pendingTools.get(toolCallId);
        if (!meta) return;
        // 幂等：同一个 toolCallId 只渲染一次卡片。
        // HITL 续推后 handleHitlResume 会同时遍历 addedMessages（含 pending toolResult）
        // 和 json.pendingTools，两个路径都会触达本函数；这里去重避免渲染两个并列卡片。
        if (document.querySelector(`[data-hitl-id="${CSS.escape(toolCallId)}"]`)) return;
        clearEmptyHint();
        const msg = el('div', 'msg tool');
        msg.appendChild(el('div', 'avatar', avatarText('TOOL')));
        const wrap = el('div', 'bubble-wrap');
        const card = el('div', 'tool-step hitl-card');
        card.dataset.hitlId = toolCallId;
        const head = el('div', 'tool-step-head');
        head.appendChild(svgIcon('M12 8v4M12 16h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z', 14));
        head.appendChild(el('span', 'tool-name', meta.name === ASK_USER ? '需要你的输入' : '需要人工审批：' + meta.name));
        card.appendChild(head);
        const body = el('div', 'hitl-body');
        if (meta.name === ASK_USER) {
            renderAskUserInto(body, meta.input || {}, toolCallId, card);
        } else {
            renderApproveRejectInto(body, meta.input || {}, toolCallId, card);
        }
        card.appendChild(body);
        wrap.appendChild(card);
        msg.appendChild(wrap);
        chatArea.appendChild(msg);
        chatArea.scrollTop = chatArea.scrollHeight;
    }

    /** 通用："已提交"/"已批准"占位，把审批卡片 disabled 掉。 */
    function lockHitlCard(card, summary) {
        card.classList.add('locked');
        card.querySelectorAll('input, textarea, select, button').forEach(e => e.disabled = true);
        const tag = el('div', 'hitl-done', summary);
        card.appendChild(tag);
    }

    /** 立即把卡片切到"提交中"视觉状态：禁用所有控件，主按钮显示 loading 文字。 */
    function markHitlCardSubmitting(card, busyLabel) {
        if (!card) return;
        card.classList.add('submitting');
        card.querySelectorAll('input, textarea, select, button').forEach(e => e.disabled = true);
        const primary = card.querySelector('.hitl-btn.primary');
        if (primary) {
            primary.dataset.originalText = primary.textContent;
            primary.textContent = busyLabel || '提交中…';
        }
    }

    /** 把人工答复 / 批准结果发回 agent 续推；UI 上像普通"再发一次"那样接 ReAct 流。 */
    async function submitToolReply(toolCallId, text, card) {
        if (isBusy) return;
        isBusy = true;
        markHitlCardSubmitting(card, '提交中…');
        setSendButtonMode('stop');
        setStatus('busy');
        try {
            const res = await fetch(
                `/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}`,
                { method: 'POST', headers: {'Content-Type':'application/json'},
                  body: JSON.stringify({ text, role: 'TOOL', toolCallId }) });
            const j = await res.json();
            handleHitlResume(j, toolCallId, card);
        } catch (e) {
            alert('提交失败：' + e);
        } finally {
            isBusy = false;
            setSendButtonMode('send');
            setStatus('ready');
        }
    }

    /** 后端真正执行该工具，结果回填给 agent 续推。 */
    async function approveTool(toolCallId, card) {
        if (isBusy) return;
        isBusy = true;
        markHitlCardSubmitting(card, '执行中…');
        setSendButtonMode('stop');
        setStatus('busy');
        try {
            const res = await fetch(
                `/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}/approve-tool`,
                { method: 'POST', headers: {'Content-Type':'application/json'},
                  body: JSON.stringify({ toolCallId }) });
            const j = await res.json();
            handleHitlResume(j, toolCallId, card);
        } catch (e) {
            alert('批准失败：' + e);
        } finally {
            isBusy = false;
            setSendButtonMode('send');
            setStatus('ready');
        }
    }

    /** 共享的 HITL 续推后处理：渲染本轮 added 消息 + 处理是否仍然挂起。 */
    function handleHitlResume(json, toolCallId, card) {
        pendingTools.delete(toolCallId);
        if (card) lockHitlCard(card, '已提交');
        (json.addedMessages || []).forEach(appendApiMessageStatic);
        // 若续推后仍在等待人工输入（例如多个 ask_user 串行），把新的 pending 工具的 UI 渲染出来
        if (json.awaitingHumanInput && Array.isArray(json.pendingTools)) {
            json.pendingTools.forEach(t => {
                pendingTools.set(t.id, { name: t.name, input: t.input || {} });
                renderHitlPrompt(t.id);
            });
        }
    }

    /** 把 ask_user 的 ui_type 翻译为输入控件。返回控件读取函数 reader()。 */
    function renderAskUserInto(body, input, toolCallId, card) {
        const question = input.question || '';
        if (question) body.appendChild(el('div', 'hitl-question', question));
        const uiType = (input.ui_type || 'text').toLowerCase();
        const opts = Array.isArray(input.options) ? input.options : [];
        const allowOther = !!input.allow_other;
        const def = input.default_value;

        let reader = () => '';
        if (uiType === 'select') {
            const wrap = el('div', 'hitl-options');
            let chosen = (typeof def === 'string') ? def : null;
            opts.forEach(o => {
                const b = el('button', 'hitl-opt' + (chosen === o ? ' active' : ''), o);
                b.addEventListener('click', () => {
                    chosen = o;
                    wrap.querySelectorAll('.hitl-opt').forEach(x => x.classList.remove('active'));
                    b.classList.add('active');
                });
                wrap.appendChild(b);
            });
            let otherInput = null;
            if (allowOther) {
                otherInput = el('input', 'hitl-input');
                otherInput.placeholder = '其它（自定义）';
                otherInput.addEventListener('input', () => { if (otherInput.value) chosen = otherInput.value; });
                wrap.appendChild(otherInput);
            }
            body.appendChild(wrap);
            reader = () => chosen || '';
        } else if (uiType === 'multi_select') {
            const wrap = el('div', 'hitl-options');
            const chosen = new Set(Array.isArray(def) ? def : []);
            opts.forEach(o => {
                const b = el('button', 'hitl-opt' + (chosen.has(o) ? ' active' : ''), o);
                b.addEventListener('click', () => {
                    if (chosen.has(o)) { chosen.delete(o); b.classList.remove('active'); }
                    else { chosen.add(o); b.classList.add('active'); }
                });
                wrap.appendChild(b);
            });
            let otherInput = null;
            if (allowOther) {
                otherInput = el('input', 'hitl-input');
                otherInput.placeholder = '其它（按 Enter 添加）';
                otherInput.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter' && otherInput.value.trim()) {
                        e.preventDefault();
                        chosen.add(otherInput.value.trim());
                        otherInput.value = '';
                        // 简单展示：增加一个标签
                        const tag = el('span', 'hitl-other-tag', [...chosen].pop());
                        wrap.insertBefore(tag, otherInput);
                    }
                });
                wrap.appendChild(otherInput);
            }
            body.appendChild(wrap);
            reader = () => JSON.stringify([...chosen]);
        } else if (uiType === 'confirm') {
            const wrap = el('div', 'hitl-options');
            let chosen = null;
            ['是', '否'].forEach((label, idx) => {
                const b = el('button', 'hitl-opt', label);
                b.addEventListener('click', () => {
                    chosen = idx === 0 ? 'yes' : 'no';
                    wrap.querySelectorAll('.hitl-opt').forEach(x => x.classList.remove('active'));
                    b.classList.add('active');
                });
                wrap.appendChild(b);
            });
            body.appendChild(wrap);
            reader = () => chosen || '';
        } else if (uiType === 'date' || uiType === 'number') {
            const inp = el('input', 'hitl-input');
            inp.type = uiType === 'date' ? 'date' : 'number';
            if (def != null) inp.value = String(def);
            body.appendChild(inp);
            reader = () => inp.value;
        } else if (uiType === 'form') {
            const fields = Array.isArray(input.fields) ? input.fields : [];
            const inputs = {};
            fields.forEach(f => {
                const row = el('div', 'hitl-form-row');
                row.appendChild(el('label', 'hitl-form-label', f.label || f.name));
                const inp = el('input', 'hitl-input');
                if (f.type === 'number') inp.type = 'number';
                else if (f.type === 'date') inp.type = 'date';
                else inp.type = 'text';
                if (f.placeholder) inp.placeholder = f.placeholder;
                if (f.min != null) inp.min = f.min;
                if (f.max != null) inp.max = f.max;
                if (f.step != null) inp.step = f.step;
                row.appendChild(inp);
                inputs[f.name] = inp;
                body.appendChild(row);
            });
            reader = () => {
                const out = {};
                Object.entries(inputs).forEach(([k, v]) => { out[k] = v.value; });
                return JSON.stringify(out);
            };
        } else {
            // 默认 text
            const inp = el('textarea', 'hitl-input hitl-textarea');
            inp.rows = 2;
            if (typeof def === 'string') inp.value = def;
            inp.placeholder = '输入你的回答…';
            body.appendChild(inp);
            reader = () => inp.value;
        }

        const actions = el('div', 'hitl-actions');
        const submitBtn = el('button', 'hitl-btn primary', '提交');
        submitBtn.addEventListener('click', () => {
            const v = reader();
            if (v == null || v === '') {
                alert('请填写内容');
                return;
            }
            submitToolReply(toolCallId, v, card);
        });
        actions.appendChild(submitBtn);
        body.appendChild(actions);
    }

    /** 普通工具的"批准 / 拒绝"UI。 */
    function renderApproveRejectInto(body, input, toolCallId, card) {
        body.appendChild(el('div', 'hitl-question',
                '该工具被标记为需要人工审批。批准则执行，拒绝则把"已拒绝"作为结果回填。'));
        body.appendChild(el('pre', 'tool-args', JSON.stringify(input, null, 2)));
        const reasonInput = el('input', 'hitl-input');
        reasonInput.placeholder = '拒绝理由（可选）';
        body.appendChild(reasonInput);
        const actions = el('div', 'hitl-actions');
        const approveBtn = el('button', 'hitl-btn primary', '批准并执行');
        approveBtn.addEventListener('click', () => approveTool(toolCallId, card));
        const rejectBtn = el('button', 'hitl-btn', '拒绝');
        rejectBtn.addEventListener('click', () => {
            const reason = reasonInput.value.trim() || '用户拒绝执行';
            submitToolReply(toolCallId, reason, card);
        });
        actions.appendChild(approveBtn);
        actions.appendChild(rejectBtn);
        body.appendChild(actions);
    }

    /** providers 列表（含 displayName + modelName）→ id 索引，便于按 id 查 displayName/modelName */
    function providerById(id) {
        return (providersInfo.providers || []).find(p => p.id === id);
    }

    /** 根据当前会话的 provider 更新 header 上的模型标签。 */
    function updateModelTag(conv) {
        if (!modelTag) return;
        const pid = conv && conv.provider ? conv.provider : providersInfo.defaultId;
        const p = providerById(pid);
        modelTag.textContent = p ? p.modelName : '';
        modelTag.title = p ? `${p.displayName} · ${p.modelName}` : '';
    }

    // ---------- 会话列表（侧边栏）----------
    async function loadConversations() {
        try {
            const res = await fetch(`/api/users/${encodeURIComponent(userId)}/conversations`);
            if (!res.ok) return;
            const list = await res.json();
            renderConvList(list);
            const current = list.find(c => c.id === convId);
            convTitle.textContent = current ? current.title : '…';
            updateModelTag(current);
        } catch (e) {
            console.warn('加载会话列表失败', e);
        }
    }

    function renderConvList(list) {
        convList.innerHTML = '';
        if (list.length === 0) {
            convList.appendChild(el('div', 'conv-item', '尚无对话'));
            return;
        }
        list.forEach(c => {
            const item = el('a', 'conv-item' + (c.id === convId ? ' active' : ''));
            item.href = `/?user=${encodeURIComponent(userId)}&conv=${encodeURIComponent(c.id)}`;
            const title = el('span', 'conv-item-title', c.title);
            // 若该会话指定了非默认 provider，加一个小标签便于一眼识别
            if (c.provider && providerById(c.provider)) {
                const tag = el('span', 'conv-provider-tag', providerById(c.provider).displayName);
                title.appendChild(tag);
            }
            const time = el('span', 'conv-item-time', relativeTime(c.updatedAt));
            const del = el('button', 'conv-del', '');
            del.appendChild(svgIcon('M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6h14z', 13));
            del.addEventListener('click', (ev) => {
                ev.preventDefault();
                ev.stopPropagation();
                deleteConversation(c.id);
            });
            item.appendChild(title);
            item.appendChild(time);
            item.appendChild(del);
            convList.appendChild(item);
        });
    }

    /** 拉取启用的 provider 列表，缓存到 providersInfo。 */
    async function loadProviders() {
        try {
            const res = await fetch('/api/providers');
            if (!res.ok) return;
            providersInfo = await res.json();
        } catch (e) {
            console.warn('加载 providers 失败', e);
        }
    }

    /** 打开 provider 选择弹窗，确认后创建会话。 */
    function openProviderModal() {
        if (!providersInfo.providers || providersInfo.providers.length === 0) {
            // 后端未启用任何 provider；按老逻辑直接建（会用默认）
            return createConversationWith(null);
        }
        modalSelectedProvider = providersInfo.defaultId || providersInfo.providers[0].id;
        renderProviderOptions();
        providerModal.hidden = false;
    }

    function renderProviderOptions() {
        providerListEl.innerHTML = '';
        providersInfo.providers.forEach(p => {
            const opt = el('div', 'provider-option' + (p.id === modalSelectedProvider ? ' selected' : ''));
            const radio = el('div', 'radio');
            const label = el('div', 'label');
            label.appendChild(el('div', 'label-name', p.displayName));
            label.appendChild(el('div', 'label-model', p.modelName));
            opt.appendChild(radio);
            opt.appendChild(label);
            opt.addEventListener('click', () => {
                modalSelectedProvider = p.id;
                renderProviderOptions();
            });
            providerListEl.appendChild(opt);
        });
    }

    function closeProviderModal() {
        providerModal.hidden = true;
    }

    async function createConversationWith(provider) {
        const body = provider ? JSON.stringify({ provider }) : '{}';
        const res = await fetch(`/api/users/${encodeURIComponent(userId)}/conversations`,
                { method: 'POST', headers: {'Content-Type': 'application/json'}, body });
        if (res.ok) {
            const c = await res.json();
            location.href = `/?user=${encodeURIComponent(userId)}&conv=${encodeURIComponent(c.id)}`;
        }
    }

    async function deleteConversation(id) {
        if (!confirm('确认删除这个对话？此操作不可恢复。')) return;
        await fetch(`/api/users/${encodeURIComponent(userId)}/conversations/${encodeURIComponent(id)}`,
                { method: 'DELETE' });
        if (id === convId) {
            // 删的是当前对话 → 跳到默认页让服务端重新挑/建一个
            location.href = `/?user=${encodeURIComponent(userId)}`;
        } else {
            loadConversations();
        }
    }

    async function renameConversation() {
        const newTitle = prompt('新的对话标题：', convTitle.textContent);
        if (!newTitle || !newTitle.trim()) return;
        const res = await fetch(`/api/users/${encodeURIComponent(userId)}/conversations/${encodeURIComponent(convId)}`,
                { method: 'PUT', headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify({ title: newTitle.trim() }) });
        if (res.ok) {
            const c = await res.json();
            convTitle.textContent = c.title;
            loadConversations();
        }
    }

    // ---------- 加载历史 ----------
    async function loadHistory() {
        try {
            const res = await fetch(`/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}/history`);
            if (!res.ok) return;
            const list = await res.json();
            if (list.length === 0) return;
            clearEmptyHint();
            list.forEach(appendApiMessageStatic);
        } catch (e) {
            console.warn('加载历史失败', e);
        }
    }

    // ---------- 中断 ----------
    async function interruptCurrent() {
        if (!isBusy) return;
        // 立刻给视觉反馈 —— 把按钮先恢复到 send 模式，避免"我点了停止但还在转"的错觉
        // EventSource.close() 主动关流不会触发 onerror，所以必须在这里自己复位状态
        const esToClose = currentEs;
        currentEs = null;
        isBusy = false;
        setSendButtonMode('send');
        setStatus('ready');
        if (esToClose) esToClose.close();
        try {
            await fetch(`/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}/interrupt`,
                    { method: 'POST' });
        } catch (e) { /* 后端可能已自然结束，忽略错误 */ }
        input.focus();
    }

    // ---------- 发送 ----------
    function sendMessage(text) {
        if (!text.trim() || isBusy) return;
        isBusy = true;
        // 不再 disable，而是变成 stop 按钮
        setSendButtonMode('stop');
        setStatus('busy');

        appendUserMessage(text);
        input.value = '';
        autoResizeInput();

        const url = `/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}/react-stream?text=${encodeURIComponent(text)}`;
        const es = new EventSource(url);
        currentEs = es;

        let currentText = null, currentThinking = null;
        let currentTextAcc = '', currentThinkingAcc = '';
        let finished = false;

        es.onmessage = (ev) => {
            let data;
            try { data = JSON.parse(ev.data); } catch (e) { console.warn('[chat] SSE parse fail', e, ev.data); return; }
            // 调试：在控制台打印每个 SSE 事件的关键字段，便于诊断 HITL 渲染等问题
            console.debug('[chat-sse]', data.type, data.id || '', data.name || '', data.pending !== undefined ? ('pending=' + data.pending) : '');
            switch (data.type) {
                case 'iter':
                    currentText = null;
                    currentThinking = null;
                    currentTextAcc = '';
                    currentThinkingAcc = '';
                    break;
                case 'thinking':
                    if (!currentThinking) currentThinking = startThinkingBubble();
                    currentThinkingAcc += data.delta;
                    currentThinking.body.textContent = currentThinkingAcc;
                    chatArea.scrollTop = chatArea.scrollHeight;
                    break;
                case 'text':
                    if (!currentText) {
                        currentText = startAssistantBubble();
                        currentText.bubble.classList.add('markdown');
                    }
                    currentTextAcc += data.delta;
                    // 流式过程中也渲染 markdown，每个 chunk 重新解析一次（marked 很快，几 ms 量级）
                    currentText.bubble.innerHTML = renderMarkdown(currentTextAcc);
                    chatArea.scrollTop = chatArea.scrollHeight;
                    break;
                case 'tool_call':
                    // 优先把 meta 入 pendingTools，确保即便 appendToolCall 渲染抛错，
                    // done 阶段的兜底 + 后续 tool_result 处理仍能找到正确的 name/input。
                    pendingTools.set(data.id, { name: data.name, input: data.input || {} });
                    if (currentText && currentText.bubble) currentText.bubble.classList.remove('streaming');
                    if (currentThinking) currentThinking.summary.querySelector('span').textContent = '思考完成';
                    appendToolCall({ id: data.id, name: data.name, input: data.input });
                    currentText = null;
                    currentThinking = null;
                    break;
                case 'tool_result':
                    if (data.pending) {
                        // pending 工具：直接渲染 HITL 卡片（不渲染普通"待审批"卡，避免视觉重叠）。
                        // 兜底：若 pendingTools 没有这条 meta（流式 tool_call 与 tool_result 的 id
                        // 在某些 provider 下可能不一致，或 tool_call 漏发），用 name+output 注册一个
                        // 默认 text 类型条目，至少让用户能输入文字回复，避免卡死。
                        if (!pendingTools.has(data.id)) {
                            pendingTools.set(data.id, {
                                name: data.name || 'unknown',
                                input: { question: data.output, ui_type: 'text' }
                            });
                        }
                        renderHitlPrompt(data.id);
                    } else {
                        appendToolResult({ id: data.id, output: data.output, isError: data.isError, pending: false });
                        pendingTools.delete(data.id);
                    }
                    break;
                case 'done':
                    if (currentText && currentText.bubble) {
                        currentText.bubble.classList.remove('streaming');
                        if (data.usage && Object.keys(data.usage).length > 0) {
                            currentText.wrap.appendChild(buildMeta(data.usage));
                        }
                    }
                    if (currentThinking) currentThinking.summary.querySelector('span').textContent = '思考完成';
                    // HITL 兜底：流式期间任何原因导致 pending 工具的 HITL 卡片漏渲染（事件顺序、
                    // SSE 链路丢包、个别 provider 不发 tool_result 等），都在 done 时统一补上。
                    // pendingTools 此时只剩"未应答"的工具（非 pending 的已在 tool_result 时被 delete）。
                    pendingTools.forEach((meta, id) => {
                        if (!document.querySelector(`[data-hitl-id="${CSS.escape(id)}"]`)) {
                            renderHitlPrompt(id);
                        }
                    });
                    // DOM 级兜底：极端情况下 pendingTools 也是空的（tool_call 事件未触达前端）。
                    // 扫描所有"调用 ask_user"金色卡片，对没有对应 HITL 卡片的，从卡片自身解析
                    // name/input 后补一个 HITL UI，确保用户至少能输入回复。
                    document.querySelectorAll('[data-tool-call-id]').forEach(callCard => {
                        const id = callCard.dataset.toolCallId;
                        if (document.querySelector(`[data-hitl-id="${CSS.escape(id)}"]`)) return;
                        if (!pendingTools.has(id)) {
                            const name = callCard.dataset.toolName || '';
                            let input = {};
                            try {
                                const argsEl = callCard.querySelector('.tool-args');
                                if (argsEl && argsEl.textContent) input = JSON.parse(argsEl.textContent);
                            } catch (e) { /* 容错：解析失败用空 input */ }
                            pendingTools.set(id, { name, input });
                        }
                        renderHitlPrompt(id);
                    });
                    finished = true;
                    es.close();
                    currentEs = null;
                    isBusy = false;
                    setSendButtonMode('send');
                    setStatus('ready');
                    input.focus();
                    // 第一次发消息后会刷新标题，重新加载会话列表把它移到顶
                    loadConversations();
                    break;
            }
        };

        es.onerror = () => {
            es.close();
            currentEs = null;
            if (!finished) {
                if (currentText && currentText.bubble) {
                    currentText.bubble.classList.remove('streaming');
                    if (currentTextAcc.length === 0) {
                        currentText.bubble.classList.remove('markdown');
                        currentText.bubble.textContent = '（已中断）';
                    } else {
                        // 部分内容已渲染，加个中断提示
                        const tag = document.createElement('div');
                        tag.className = 'interrupt-tag';
                        tag.textContent = '— 已中断 —';
                        currentText.wrap.appendChild(tag);
                    }
                }
                setStatus('ready'); // 不算 error，是用户主动中断
            }
            isBusy = false;
            setSendButtonMode('send');
            input.focus();
        };
    }

    // ---------- 输入框 ----------
    function autoResizeInput() {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    }
    input.addEventListener('input', autoResizeInput);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage(input.value);
        }
    });
    sendBtn.addEventListener('click', () => {
        if (isBusy) interruptCurrent();
        else sendMessage(input.value);
    });

    document.querySelectorAll('.example-prompts li').forEach(li => {
        li.addEventListener('click', () => {
            input.value = li.textContent.trim();
            autoResizeInput();
            input.focus();
        });
    });

    newConvBtn.addEventListener('click', openProviderModal);
    modalCancel.addEventListener('click', closeProviderModal);
    modalConfirm.addEventListener('click', () => {
        const pid = modalSelectedProvider;
        closeProviderModal();
        createConversationWith(pid);
    });
    // 点遮罩空白处也关闭
    providerModal.addEventListener('click', (ev) => {
        if (ev.target === providerModal) closeProviderModal();
    });
    renameBtn.addEventListener('click', renameConversation);
    compactBtn.addEventListener('click', async () => {
        if (!confirm('确认压缩当前会话上下文？\n这会调用 LLM 把旧消息摘要为一条 system 消息，节省后续对话的 token，但会丢失旧消息的细节。')) return;
        const originalTitle = compactBtn.title;
        compactBtn.disabled = true;
        compactBtn.title = '压缩中…';
        try {
            const r = await fetch(`/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}/compact`, { method: 'POST' });
            const j = await r.json();
            if (!j.ok) {
                alert('压缩失败：' + (j.error || '未知错误'));
                return;
            }
            if (j.before === j.after) {
                alert(`无可压缩内容（当前 ${j.before} 条消息）`);
            } else {
                alert(`压缩完成：${j.before} 条 → ${j.after} 条`);
                await loadHistory();
            }
        } catch (err) {
            alert('压缩请求失败：' + err);
        } finally {
            compactBtn.disabled = false;
            compactBtn.title = originalTitle;
        }
    });
    resetBtn.addEventListener('click', async () => {
        if (!confirm('确认清空当前会话的消息？（会话本身保留）')) return;
        if (currentEs) currentEs.close();
        await fetch(`/api/chat/${encodeURIComponent(userId)}/${encodeURIComponent(convId)}`, { method: 'DELETE' });
        chatArea.innerHTML = '';
        chatArea.appendChild(emptyHint);
        emptyHint.style.display = '';
        setStatus('ready');
    });

    // ---------- 启动 ----------
    // 先拉 providers，让首次 loadConversations 的 model-tag 能正确显示
    loadProviders().then(() => loadConversations());
    loadHistory();

    // 每 60 秒刷新一次会话列表（更新相对时间显示）
    setInterval(loadConversations, 60000);
})();
