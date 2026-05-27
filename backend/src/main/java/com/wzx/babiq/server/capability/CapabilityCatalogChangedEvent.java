package com.wzx.babiq.server.capability;

/**
 * 能力目录变化事件。
 *
 * <p>`CapabilityCatalogSyncService` 在把本地工具、MCP 工具和 Skill metadata 同步到 SQLite 后发布该事件。
 * 搜索索引、未来的缓存或观测组件只监听事件，不反向耦合同步服务，从而保持“SQLite 是事实源、索引是派生物”的边界。</p>
 *
 * @param source 触发事件的来源对象；目前主要是同步服务实例，测试中也可以传入任意来源
 */
public record CapabilityCatalogChangedEvent(Object source) {
}
