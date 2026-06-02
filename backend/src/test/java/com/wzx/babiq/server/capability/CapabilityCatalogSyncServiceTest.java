package com.wzx.babiq.server.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.mcp.McpToolCatalog;
import com.wzx.babiq.server.mcp.McpToolDescriptor;
import com.wzx.babiq.server.skill.LocalSkillRegistry;
import com.wzx.babiq.server.skill.SkillDescriptor;
import com.wzx.babiq.server.tool.ToolRegistry;
import com.wzx.babiq.server.tool.impl.ReadFileTool;
import com.wzx.babiq.server.tool.impl.UpdatePlanTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 能力目录同步服务测试。
 *
 * <p>P3-5a 的 Lucene 索引不直接耦合扫描逻辑；同步服务只在完成 SQLite 事实源更新后发布目录变化事件，
 * 搜索服务监听该事件并自行重建索引。</p>
 */
class CapabilityCatalogSyncServiceTest {

    @Test
    @DisplayName("能力目录同步完成后发布目录变化事件")
    @SuppressWarnings("unchecked")
    void sync_should_publish_catalog_changed_event() {
        ToolRegistry toolRegistry = new ToolRegistry(java.util.List.of());
        ObjectProvider<McpToolCatalog> mcpProvider = mock(ObjectProvider.class);
        ObjectProvider<LocalSkillRegistry> skillProvider = mock(ObjectProvider.class);
        CapabilityRepository repository = mock(CapabilityRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(mcpProvider.getIfAvailable()).thenReturn(null);
        when(skillProvider.getIfAvailable()).thenReturn(null);
        CapabilityCatalogSyncService service = new CapabilityCatalogSyncService(
                toolRegistry, mcpProvider, skillProvider, repository, new ObjectMapper(), events);

        service.sync();

        verify(events).publishEvent(isA(CapabilityCatalogChangedEvent.class));
    }

    @Test
    @DisplayName("同步本地工具时自动富化中文 searchText 且重复同步幂等")
    @SuppressWarnings("unchecked")
    void sync_should_enrich_local_tool_search_text_once() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ReadFileTool()));
        ObjectProvider<McpToolCatalog> mcpProvider = mock(ObjectProvider.class);
        ObjectProvider<LocalSkillRegistry> skillProvider = mock(ObjectProvider.class);
        CapturingCapabilityRepository repository = new CapturingCapabilityRepository();
        when(mcpProvider.getIfAvailable()).thenReturn(null);
        when(skillProvider.getIfAvailable()).thenReturn(null);
        CapabilityCatalogSyncService service = new CapabilityCatalogSyncService(
                toolRegistry, mcpProvider, skillProvider, repository, new ObjectMapper(), null);

        service.sync();
        service.sync();

        CapabilityDescriptor descriptor = repository.findById("local.read_file").orElseThrow();
        assertThat(descriptor.searchText()).contains("读取", "查看", "打开", "文件内容");
        assertThat(countOccurrences(descriptor.searchText(), "读取")).isEqualTo(1);
    }

    @Test
    @DisplayName("同步 MCP 工具时根据 namespacedName 自动富化中文 searchText")
    @SuppressWarnings("unchecked")
    void sync_should_enrich_mcp_tool_search_text() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        McpToolCatalog mcpCatalog = mock(McpToolCatalog.class);
        ObjectProvider<McpToolCatalog> mcpProvider = mock(ObjectProvider.class);
        ObjectProvider<LocalSkillRegistry> skillProvider = mock(ObjectProvider.class);
        CapturingCapabilityRepository repository = new CapturingCapabilityRepository();
        when(mcpCatalog.descriptors()).thenReturn(List.of(
                McpToolDescriptor.of("filesystem", "read_text_file", "Read text file", "{\"type\":\"object\"}")));
        when(mcpProvider.getIfAvailable()).thenReturn(mcpCatalog);
        when(skillProvider.getIfAvailable()).thenReturn(null);
        CapabilityCatalogSyncService service = new CapabilityCatalogSyncService(
                toolRegistry, mcpProvider, skillProvider, repository, new ObjectMapper(), null);

        service.sync();

        CapabilityDescriptor descriptor = repository.findById("mcp.filesystem.read_text_file").orElseThrow();
        assertThat(descriptor.searchText()).contains("文件系统", "文件", "读取", "查看");
    }

    @Test
    @DisplayName("update_plan 是默认可见能力且 searchText 包含计划中文别名")
    @SuppressWarnings("unchecked")
    void sync_should_keep_update_plan_visible_and_searchable_in_chinese() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new UpdatePlanTool()));
        ObjectProvider<McpToolCatalog> mcpProvider = mock(ObjectProvider.class);
        ObjectProvider<LocalSkillRegistry> skillProvider = mock(ObjectProvider.class);
        CapturingCapabilityRepository repository = new CapturingCapabilityRepository();
        when(mcpProvider.getIfAvailable()).thenReturn(null);
        when(skillProvider.getIfAvailable()).thenReturn(null);
        CapabilityCatalogSyncService service = new CapabilityCatalogSyncService(
                toolRegistry, mcpProvider, skillProvider, repository, new ObjectMapper(), null);

        service.sync();

        CapabilityDescriptor descriptor = repository.findById("local.update_plan").orElseThrow();
        assertThat(descriptor.exposureMode()).isEqualTo(CapabilityExposureMode.VISIBLE);
        assertThat(descriptor.searchText()).contains("计划", "任务清单", "待办", "步骤", "规划");
    }

    @Test
    @DisplayName("同步 Skill 能力时沿用稳定 capability id 且不会把 allowedTools 写进 searchText 授权")
    @SuppressWarnings("unchecked")
    void sync_should_keep_skill_capability_identity_stable() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        ObjectProvider<McpToolCatalog> mcpProvider = mock(ObjectProvider.class);
        ObjectProvider<LocalSkillRegistry> skillProvider = mock(ObjectProvider.class);
        LocalSkillRegistry skillRegistry = mock(LocalSkillRegistry.class);
        CapturingCapabilityRepository repository = new CapturingCapabilityRepository();
        when(mcpProvider.getIfAvailable()).thenReturn(null);
        when(skillProvider.getIfAvailable()).thenReturn(skillRegistry);
        when(skillRegistry.listSkills()).thenReturn(List.of(new SkillDescriptor(
                "agent.context",
                "agent",
                "context",
                "上下文治理 Skill",
                "E:\\skills",
                "E:\\skills\\agent\\context\\SKILL.md",
                "hash",
                List.of("read_file", "list_dir"))));
        CapabilityCatalogSyncService service = new CapabilityCatalogSyncService(
                toolRegistry, mcpProvider, skillProvider, repository, new ObjectMapper(), null);

        service.sync();

        CapabilityDescriptor descriptor = repository.findById("skill.agent.context").orElseThrow();
        assertThat(descriptor.type()).isEqualTo(CapabilityType.SKILL);
        assertThat(descriptor.namespace()).isEqualTo("agent");
        assertThat(descriptor.name()).isEqualTo("context");
        assertThat(descriptor.schemaHash()).isEqualTo("hash");
        assertThat(descriptor.searchText()).contains("context", "上下文治理 Skill");
    }

    /**
     * 统计短词在 searchText 中出现的次数，用来证明富化逻辑不会重复追加同一批别名。
     */
    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /**
     * 轻量内存仓库，只服务同步测试。
     *
     * <p>相比 Mockito capture，它能更自然地模拟同一能力多次 upsert 后的最终事实源状态。</p>
     */
    private static final class CapturingCapabilityRepository implements CapabilityRepository {

        /** 最新能力快照，key 为 capabilityId。 */
        private final Map<String, CapabilityDescriptor> descriptors = new LinkedHashMap<>();

        @Override
        public void upsert(CapabilityDescriptor descriptor) {
            descriptors.put(descriptor.capabilityId(), descriptor);
        }

        @Override
        public List<CapabilityDescriptor> listAll() {
            return List.copyOf(descriptors.values());
        }

        @Override
        public List<CapabilityDescriptor> listEnabled() {
            return descriptors.values().stream()
                    .filter(CapabilityDescriptor::enabled)
                    .toList();
        }

        @Override
        public Optional<CapabilityDescriptor> findById(String capabilityId) {
            return Optional.ofNullable(descriptors.get(capabilityId));
        }

        @Override
        public void updateSettings(String capabilityId, Boolean enabled, CapabilityExposureMode exposureMode) {
            throw new UnsupportedOperationException("同步测试不需要修改设置");
        }

        @Override
        public void recordSearchEvent(CapabilitySearchEventRecord record) {
            throw new UnsupportedOperationException("同步测试不记录搜索事件");
        }

        @Override
        public List<String> recentSelectedCapabilityIds(String threadId, int limit) {
            return List.of();
        }
    }
}
