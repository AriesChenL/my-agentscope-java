-- =====================================================================
-- agentscope 分布式部署初始 schema
-- 仅当 agentscope.cluster.mode=distributed 时由 Flyway 在启动时创建
-- =====================================================================

-- 对话目录：每个用户有多个独立对话
CREATE TABLE conversations (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    conv_id     VARCHAR(64) NOT NULL,
    title       TEXT,
    provider    VARCHAR(32),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- 自然唯一约束（业务键）
    CONSTRAINT uk_conversations_user_conv UNIQUE (user_id, conv_id)
);
-- 列出某用户对话按更新时间倒序的查询会非常频繁
CREATE INDEX idx_conv_user_updated ON conversations (user_id, updated_at DESC);

COMMENT ON TABLE  conversations          IS '用户的对话目录（user_id+conv_id 唯一）';
COMMENT ON COLUMN conversations.id       IS '代理主键，方便日志/审计单列引用';
COMMENT ON COLUMN conversations.provider IS '该对话使用的 LLM provider：openai/anthropic/gemini，nullable 时走 default';

-- 会话状态（memory + 未来可扩展的其它 slot）
CREATE TABLE session_memory (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 与代码层 SessionKey.value() 一致，形如 "alice__c-001"
    session_key VARCHAR(128) NOT NULL,
    -- 当前只有 'memory' 一个 slot，预留其它（如 long_term_memory）
    slot        VARCHAR(32)  NOT NULL DEFAULT 'memory',
    -- JSONB 存 Msg[] 序列化结果；PG 自动 TOAST 处理大字段
    payload     JSONB        NOT NULL,
    -- 乐观锁版本号；本期未启用，预留给 PR 3 跨节点并发场景
    version     BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_session_memory UNIQUE (session_key, slot)
);

COMMENT ON TABLE  session_memory             IS '会话级持久化数据（memory 历史等）';
COMMENT ON COLUMN session_memory.session_key IS '与代码层 SessionKey.value() 完全一致，形如 alice__c-001';
COMMENT ON COLUMN session_memory.slot        IS '槽位名，由组件约定（Memory 用 memory）';
COMMENT ON COLUMN session_memory.payload     IS 'JSONB 序列化对象；Msg[] 走 Jackson @JsonTypeInfo 多态';
COMMENT ON COLUMN session_memory.version     IS '乐观锁，本期未启用，预留';
