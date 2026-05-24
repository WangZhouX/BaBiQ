package com.wzx.babiq.server.conversation.repository;

import java.time.Instant;

/**
 * AppSetting 持久化边界使用的领域记录。
 *
 * <p>P2-1 只提供通用 key/value 存储；P2-3 会在上层定义具体 key、默认值、校验和桌面端表单。</p>
 *
 * @param settingKey 设置 key，例如 sandbox.mode
 * @param settingValue 设置值，统一保存为字符串
 * @param valueType 值类型，例如 string、boolean、number、json
 * @param updatedAt 更新时间
 */
public record AppSettingRecord(
        String settingKey,
        String settingValue,
        String valueType,
        Instant updatedAt
) {
}
