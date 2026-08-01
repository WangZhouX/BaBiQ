package com.wzx.babiq.server.capability;

import com.wzx.babiq.server.application.policy.BusinessAgentModePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力暴露计划测试。
 *
 * <p>Planner 是 P3-5 的关键边界：能力可以已注册，但模型每轮只能看到 VISIBLE、
 * 以及最近通过 tool_search 命中过的少量 DEFERRED 能力。</p>
 */
class CapabilityExposurePlannerTest {

    @Test
    @DisplayName("Planner 默认暴露 VISIBLE 能力，并把 deferred/disabled 写入计划")
    void plan_should_split_visible_deferred_and_disabled_capabilities() {
        InMemoryCapabilityRepository repository = new InMemoryCapabilityRepository();
        repository.upsert(capability("local.read_file", "read_file", CapabilityExposureMode.VISIBLE, true));
        repository.upsert(capability("mcp.fs.read", "mcp.fs.read", CapabilityExposureMode.DEFERRED, true));
        repository.upsert(capability("skill.tdd", "tdd", CapabilityExposureMode.DISABLED, true));
        CapabilityExposurePlanner planner = new CapabilityExposurePlanner(repository, () -> {});

        CapabilityExposurePlan plan = planner.plan("thr_1", "turn_1");

        assertThat(plan.visibleToolNames()).containsExactly("read_file");
        assertThat(plan.deferredCapabilityIds()).containsExactly("mcp.fs.read");
        assertThat(plan.disabledCapabilityIds()).containsExactly("skill.tdd");
    }

    @Test
    @DisplayName("Planner 会把上一轮 tool_search 命中过的 deferred tool 加入下一轮可见工具")
    void plan_should_promote_recent_selected_deferred_tool_for_next_turn() {
        InMemoryCapabilityRepository repository = new InMemoryCapabilityRepository();
        repository.upsert(capability("mcp.fs.read", "mcp.fs.read", CapabilityExposureMode.DEFERRED, true));
        repository.recentSelected = List.of("mcp.fs.read");
        CapabilityExposurePlanner planner = new CapabilityExposurePlanner(repository, () -> {});

        CapabilityExposurePlan plan = planner.plan("thr_1", "turn_2");

        assertThat(plan.visibleToolNames()).containsExactly("mcp.fs.read");
        assertThat(plan.reason()).contains("recent_tool_search");
    }

    @Test
    @DisplayName("业务模式忽略已注册能力和搜索提升并固定暴露受控工作台工具")
    void business_mode_should_use_the_fixed_model_tool_allowlist() {
        InMemoryCapabilityRepository repository = new InMemoryCapabilityRepository();
        repository.upsert(capability("local.read_file", "read_file", CapabilityExposureMode.VISIBLE, true));
        repository.upsert(capability("local.application_action", "application_action", CapabilityExposureMode.DISABLED, false));
        repository.upsert(capability("local.business_workbench_read", "business_workbench_read", CapabilityExposureMode.VISIBLE, true));
        repository.upsert(capability("local.business_schedule_mutate", "business_schedule_mutate", CapabilityExposureMode.VISIBLE, true));
        repository.upsert(capability("local.update_plan", "update_plan", CapabilityExposureMode.VISIBLE, true));
        repository.upsert(capability("mcp.crm.search", "mcp.crm.search", CapabilityExposureMode.DEFERRED, true));
        repository.upsert(capability("skill.tdd", "tdd", CapabilityExposureMode.DEFERRED, true));
        repository.recentSelected = List.of("mcp.crm.search", "skill.tdd");
        CapabilityExposurePlanner planner = new CapabilityExposurePlanner(
                repository, () -> {}, new BusinessAgentModePolicy(true));

        CapabilityExposurePlan plan = planner.plan("thr_business", "turn_business");

        assertThat(plan.visibleToolNames()).containsExactly(
                "application_action",
                "business_workbench_read",
                "business_schedule_mutate",
                "update_plan");
        assertThat(plan.visibleCapabilityIds()).containsExactly(
                "local.application_action",
                "local.business_workbench_read",
                "local.business_schedule_mutate",
                "local.update_plan");
        assertThat(plan.reason()).isEqualTo("business_fixed_allowlist");
    }

    private static CapabilityDescriptor capability(String id, String name, CapabilityExposureMode mode, boolean enabled) {
        return new CapabilityDescriptor(id, id.startsWith("skill.") ? CapabilityType.SKILL : CapabilityType.MCP_TOOL,
                id.substring(0, id.indexOf('.')), name, name, name + " description",
                "test", "hash", name + " description", mode, enabled,
                Instant.parse("2026-05-27T00:00:00Z"));
    }

    private static final class InMemoryCapabilityRepository implements CapabilityRepository {
        private final List<CapabilityDescriptor> capabilities = new ArrayList<>();
        private List<String> recentSelected = List.of();

        @Override
        public void upsert(CapabilityDescriptor descriptor) {
            capabilities.removeIf(existing -> existing.capabilityId().equals(descriptor.capabilityId()));
            capabilities.add(descriptor);
        }

        @Override
        public List<CapabilityDescriptor> listAll() {
            return List.copyOf(capabilities);
        }

        @Override
        public List<CapabilityDescriptor> listEnabled() {
            return capabilities.stream().filter(CapabilityDescriptor::enabled).toList();
        }

        @Override
        public Optional<CapabilityDescriptor> findById(String capabilityId) {
            return capabilities.stream()
                    .filter(capability -> capability.capabilityId().equals(capabilityId))
                    .findFirst();
        }

        @Override
        public void updateSettings(String capabilityId, Boolean enabled, CapabilityExposureMode exposureMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordSearchEvent(CapabilitySearchEventRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> recentSelectedCapabilityIds(String threadId, int limit) {
            return recentSelected;
        }
    }
}
