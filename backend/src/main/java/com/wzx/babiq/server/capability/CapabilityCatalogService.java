package com.wzx.babiq.server.capability;

import com.wzx.babiq.server.api.dto.CapabilityInfo;
import com.wzx.babiq.server.api.dto.CapabilitySearchRpcResult;
import com.wzx.babiq.server.api.dto.CapabilitySettingsSetResult;
import com.wzx.babiq.server.api.dto.CapabilityStatusResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 能力目录应用服务。
 *
 * <p>JSON-RPC handler 只做参数解析，本服务负责同步运行时能力目录、查询状态和更新用户开关。
 * 这样桌面端看到的列表与 Agent 实际装配使用的是同一份 `bq_capabilities` 事实源。</p>
 */
@Service
public class CapabilityCatalogService {

    /** 能力事实源仓库。 */
    private final CapabilityRepository repository;
    /** 进程内能力扫描同步器。 */
    private final CapabilityCatalogSyncService syncService;
    /** 能力搜索服务。 */
    private final CapabilitySearchService searchService;

    /**
     * 创建能力目录应用服务。
     */
    public CapabilityCatalogService(CapabilityRepository repository,
                                    CapabilityCatalogSyncService syncService,
                                    CapabilitySearchService searchService) {
        this.repository = repository;
        this.syncService = syncService;
        this.searchService = searchService;
    }

    /**
     * 返回当前能力目录状态。
     */
    public CapabilityStatusResult status() {
        syncService.sync();
        List<CapabilityDescriptor> descriptors = repository.listAll();
        int enabled = (int) descriptors.stream().filter(CapabilityDescriptor::enabled).count();
        int visible = (int) descriptors.stream()
                .filter(descriptor -> descriptor.exposureMode() == CapabilityExposureMode.VISIBLE)
                .count();
        int deferred = (int) descriptors.stream()
                .filter(descriptor -> descriptor.exposureMode() == CapabilityExposureMode.DEFERRED)
                .count();
        int disabled = (int) descriptors.stream()
                .filter(descriptor -> !descriptor.enabled()
                        || descriptor.exposureMode() == CapabilityExposureMode.DISABLED)
                .count();
        return new CapabilityStatusResult(
                descriptors.size(),
                enabled,
                visible,
                deferred,
                disabled,
                descriptors.stream().map(this::toInfo).toList());
    }

    /**
     * 为桌面端执行能力搜索。
     */
    public CapabilitySearchRpcResult search(String query, int limit, boolean recordEvent) {
        syncService.sync();
        CapabilitySearchResult result = searchService.search(new CapabilitySearchRequest(
                null, null, query, limit <= 0 ? 8 : limit, recordEvent));
        return new CapabilitySearchRpcResult(result.strategy(), result.results().stream().map(this::toInfo).toList());
    }

    /**
     * 更新单个能力的启用状态和暴露模式。
     */
    public CapabilitySettingsSetResult updateSettings(String capabilityId,
                                                       Boolean enabled,
                                                       CapabilityExposureMode exposureMode) {
        syncService.sync();
        repository.updateSettings(capabilityId, enabled, exposureMode);
        CapabilityDescriptor updated = repository.findById(capabilityId)
                .orElseThrow(() -> new IllegalArgumentException("未知能力: " + capabilityId));
        return new CapabilitySettingsSetResult(toInfo(updated));
    }

    private CapabilityInfo toInfo(CapabilityDescriptor descriptor) {
        return new CapabilityInfo(
                descriptor.capabilityId(),
                descriptor.type().name(),
                descriptor.namespace(),
                descriptor.name(),
                descriptor.displayName(),
                descriptor.description(),
                descriptor.exposureMode().name(),
                descriptor.enabled(),
                descriptor.lastSeenAt() == null ? null : descriptor.lastSeenAt().toString());
    }
}
