package com.wzx.babiq.server.conversation.repository;

import java.time.Instant;

/**
 * Provider 配置持久化边界使用的领域记录。
 *
 * <p>这里必须只保存 secretRef，不保存明文 API Key。真正的密钥读写由 SecretStore 负责，
 * 数据库和日志只能看到引用字符串。</p>
 *
 * @param providerId Provider 稳定标识
 * @param displayName 展示名称
 * @param type Provider 类型
 * @param authMode 认证模式；为空时按 api_key 处理，兼容旧数据
 * @param baseUrl API Base URL
 * @param model 默认模型名
 * @param secretRef 密钥引用，禁止放明文密钥
 * @param contextWindow 上下文窗口大小
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ProviderConfigRecord(
        String providerId,
        String displayName,
        String type,
        String authMode,
        String baseUrl,
        String model,
        String secretRef,
        int contextWindow,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 创建 Provider 配置记录。
     *
     * @return 创建时间和更新时间一致的 Provider 配置
     */
    public static ProviderConfigRecord of(
            String providerId,
            String displayName,
            String type,
            String baseUrl,
            String model,
            String secretRef,
            int contextWindow,
            boolean enabled,
            Instant now) {
        return of(providerId, displayName, type, "api_key", baseUrl, model,
                secretRef, contextWindow, enabled, now);
    }

    /**
     * 创建带认证模式的 Provider 配置记录。
     *
     * @return 创建时间和更新时间一致的 Provider 配置
     */
    public static ProviderConfigRecord of(
            String providerId,
            String displayName,
            String type,
            String authMode,
            String baseUrl,
            String model,
            String secretRef,
            int contextWindow,
            boolean enabled,
            Instant now) {
        return new ProviderConfigRecord(providerId, displayName, type, authMode, baseUrl, model,
                secretRef, contextWindow, enabled, now, now);
    }
}
