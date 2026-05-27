package com.wzx.babiq.server.capability;

/**
 * 能力暴露模式。
 *
 * <p>注册表里的能力不等于每轮都给模型。该枚举对应 Codex 的 direct/deferred 思路，
 * 由 BaBiQ 自己保存到 SQLite，避免能力选择只存在内存里无法审计。</p>
 */
public enum CapabilityExposureMode {
    /** 默认暴露给模型，适合本地核心工具和 tool_search。 */
    VISIBLE,
    /** 只进入能力搜索索引，命中后再在后续模型调用中暴露。 */
    DEFERRED,
    /** 用户或系统禁用，不进入模型可见工具，也不进入搜索结果。 */
    DISABLED
}
