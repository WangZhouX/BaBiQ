package com.wzx.babiq.server.capability;

import com.wzx.babiq.server.application.policy.BusinessAgentModePolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单轮能力暴露规划器。
 *
 * <p>Codex 的关键设计是“工具已经注册，但不一定每轮都暴露”。BaBiQ 在这里落地同样边界：
 * VISIBLE 直接进入模型工具列表，DEFERRED 只进入搜索索引；如果上轮 `tool_search` 已命中某能力，
 * 下一轮保守地把它提升为可见，避免强行改造 SAA 当前迭代的工具列表。</p>
 */
@Service
public class CapabilityExposurePlanner {

    /** 能力目录事实源。 */
    private final CapabilityRepository repository;
    /** 能力目录同步动作；Planner 每轮前调用，确保 MCP/Skill 变更能进入目录。 */
    private final Runnable syncAction;
    /** 业务模式下覆盖普通动态暴露算法的固定白名单。 */
    private final BusinessAgentModePolicy businessPolicy;

    /**
     * 创建 Planner。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CapabilityExposurePlanner(CapabilityRepository repository,
                                     CapabilityCatalogSyncService syncService,
                                     BusinessAgentModePolicy businessPolicy) {
        this(repository, syncService::sync, businessPolicy);
    }

    /**
     * 测试构造器，允许传入 no-op sync。
     */
    public CapabilityExposurePlanner(CapabilityRepository repository, Runnable syncAction) {
        this(repository, syncAction, new BusinessAgentModePolicy(false));
    }

    /** 测试构造器，可显式选择业务模式。 */
    public CapabilityExposurePlanner(CapabilityRepository repository, Runnable syncAction,
                                     BusinessAgentModePolicy businessPolicy) {
        this.repository = repository;
        this.syncAction = syncAction == null ? () -> { } : syncAction;
        this.businessPolicy = businessPolicy == null ? new BusinessAgentModePolicy(false) : businessPolicy;
    }

    /**
     * 为当前 turn 生成能力暴露计划。
     *
     * @param threadId 当前 thread id
     * @param turnId 当前 turn id
     * @return 可传给 ToolRegistry 和 ContextWindowRuntime 的计划
     */
    public CapabilityExposurePlan plan(String threadId, String turnId) {
        syncAction.run();
        if (businessPolicy.businessMode()) {
            List<String> names = businessPolicy.modelVisibleToolNames(List.of());
            return new CapabilityExposurePlan(
                    names.stream().map(name -> "local." + name).toList(),
                    names,
                    List.of(),
                    repository.listAll().stream()
                            .map(CapabilityDescriptor::capabilityId)
                            .filter(id -> !names.contains(id.replaceFirst("^local\\.", "")))
                            .toList(),
                    "business_fixed_allowlist");
        }
        Set<String> recentlySelected = new LinkedHashSet<>(repository.recentSelectedCapabilityIds(threadId, 8));
        List<String> visibleIds = new ArrayList<>();
        List<String> visibleNames = new ArrayList<>();
        List<String> deferredIds = new ArrayList<>();
        List<String> disabledIds = new ArrayList<>();
        for (CapabilityDescriptor descriptor : repository.listAll()) {
            if (!descriptor.enabled() || descriptor.exposureMode() == CapabilityExposureMode.DISABLED) {
                disabledIds.add(descriptor.capabilityId());
                continue;
            }
            boolean visible = descriptor.exposureMode() == CapabilityExposureMode.VISIBLE
                    || recentlySelected.contains(descriptor.capabilityId());
            if (visible) {
                visibleIds.add(descriptor.capabilityId());
                visibleNames.add(descriptor.name());
            } else {
                deferredIds.add(descriptor.capabilityId());
            }
        }
        String reason = recentlySelected.isEmpty() ? "default_visible" : "default_visible+recent_tool_search";
        return new CapabilityExposurePlan(visibleIds, visibleNames, deferredIds, disabledIds, reason);
    }
}
