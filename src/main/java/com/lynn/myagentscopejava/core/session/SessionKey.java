package com.lynn.myagentscopejava.core.session;

/**
 * 会话标识，用于区分不同用户 / 对话 / 租户的持久化数据桶。
 *
 * <p>必须是文件系统安全的 slug（只允许 {@code [A-Za-z0-9_-]}），防止路径注入。
 *
 * @param value 会话标识字符串
 */
public record SessionKey(String value) {

    public SessionKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SessionKey value 必填");
        }
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "SessionKey 只允许 [A-Za-z0-9_-]，实际为：" + value);
        }
    }

    /**
     * 工厂方法。
     *
     * @param value 会话标识字符串
     * @return SessionKey 实例
     */
    public static SessionKey of(String value) {
        return new SessionKey(value);
    }
}
