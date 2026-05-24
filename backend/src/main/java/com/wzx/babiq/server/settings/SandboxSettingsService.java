package com.wzx.babiq.server.settings;

import org.springframework.stereotype.Service;

/**
 * 沙箱设置薄服务。
 *
 * <p>当前实现委托 AppSettingsService，保留独立类型是为了让 JSON-RPC handler 和未来 UI
 * 清楚地区分“沙箱策略”与其他设置。</p>
 */
@Service
public class SandboxSettingsService {

    /** 应用设置服务，负责真正的持久化和枚举校验。 */
    private final AppSettingsService appSettingsService;

    /**
     * 创建沙箱设置服务。
     *
     * @param appSettingsService 应用设置服务
     */
    public SandboxSettingsService(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    /**
     * 更新默认沙箱模式。
     *
     * @param sandboxMode SandboxMode 枚举名
     * @return 更新后的应用设置
     */
    public AppSettings setMode(String sandboxMode) {
        return appSettingsService.update(new AppSettingsService.AppSettingsUpdate(null, sandboxMode, null, null));
    }
}
