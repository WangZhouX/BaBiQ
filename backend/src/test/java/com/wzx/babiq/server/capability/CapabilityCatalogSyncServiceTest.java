package com.wzx.babiq.server.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.mcp.McpToolCatalog;
import com.wzx.babiq.server.skill.LocalSkillRegistry;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

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
}
