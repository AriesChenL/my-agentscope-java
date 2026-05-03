package com.lynn.myagentscopejava.core.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型发起的工具调用请求。
 *
 * <p>每次调用由模型分配一个 {@code id}，对应的 {@link ToolResultBlock} 必须使用相同 id 才能正确匹配。
 *
 * @param id                模型分配的调用 id，不能为空
 * @param name              工具名称，不能为空
 * @param input             JSON 解析后的参数表；{@code null} 会被规范化为空 Map
 * @param providerSignature provider 自定义的不透明签名（如 Gemini thinking 模型的 thoughtSignature）。
 *                          下一轮请求里把该 ToolUseBlock 序列化回去时必须带上，否则服务端会拒。
 *                          仅 Gemini 使用，OpenAI/Anthropic 留 null
 */
public record ToolUseBlock(String id, String name, Map<String, Object> input,
                           String providerSignature) implements ContentBlock {
    public ToolUseBlock {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ToolUseBlock id 必填");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolUseBlock name 必填");
        }
        input = input == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** 兼容旧 3 参构造：providerSignature 默认 null。 */
    public ToolUseBlock(String id, String name, Map<String, Object> input) {
        this(id, name, input, null);
    }
}
