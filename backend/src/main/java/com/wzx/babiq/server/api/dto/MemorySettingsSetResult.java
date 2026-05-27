package com.wzx.babiq.server.api.dto;

/**
 * memory/settings/set 响应。
 *
 * @param enabled 长期记忆总开关
 * @param generateEnabled 后台生成开关
 * @param readEnabled 上下文注入开关
 * @param retrievalEnabled 长期记忆检索增强开关
 */
public record MemorySettingsSetResult(
        boolean enabled,
        boolean generateEnabled,
        boolean readEnabled,
        boolean retrievalEnabled
) {
}
