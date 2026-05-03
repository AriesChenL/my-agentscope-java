package com.lynn.myagentscopejava.core.tool;

import java.util.Map;

/**
 * 工具的 JSON Schema 描述，会随聊天请求一并发送给 LLM。
 *
 * @param name        工具名（必须与模型回复 tool_calls 中的 name 一致）
 * @param description 工具功能描述
 * @param parameters  描述参数结构的 JSON Schema {@code object} 类型
 */
public record ToolSchema(String name, String description, Map<String, Object> parameters) {
}
