package com.wzx.babiq.server.model;

import java.util.Locale;

/**
 * Provider 认证模式。
 *
 * <p>API Key 是既有默认模式；OAuth CLI 用于 Anthropic ant CLI 本地登录，
 * 数据库和 JSON-RPC 协议统一使用小写 wire value。</p>
 */
public enum ProviderAuthMode {

    /** 使用 SecretStore 中保存的 API Key。 */
    API_KEY("api_key"),

    /** 使用 Anthropic ant CLI 本地 OAuth 凭证。 */
    OAUTH_CLI("oauth_cli");

    private final String wireValue;

    ProviderAuthMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 返回数据库和 JSON-RPC 使用的稳定字符串。
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * 从数据库或 JSON-RPC 字符串解析认证模式。
     *
     * @param value wire value；为空时回退 API Key，兼容旧配置
     * @return 认证模式
     */
    public static ProviderAuthMode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return API_KEY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ProviderAuthMode mode : values()) {
            if (mode.wireValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的 Provider 认证模式: " + value);
    }
}
