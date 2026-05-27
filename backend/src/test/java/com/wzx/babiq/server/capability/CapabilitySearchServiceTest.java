package com.wzx.babiq.server.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力搜索服务测试。
 *
 * <p>P3-5 不把所有工具 schema 常驻塞进 prompt，而是通过 `tool_search` 搜索延迟能力。
 * 这里先固定 BaBiQ 自有 fallback 搜索语义，后续如果接入 Lucene 或 Spring AI Community
 * 也必须保持同样的仓库审计边界。</p>
 */
class CapabilitySearchServiceTest {

    @Test
    @DisplayName("词法搜索只返回启用且非禁用的相关能力，并记录搜索事件")
    void search_should_rank_enabled_deferred_capabilities_and_record_event() {
        InMemoryCapabilityRepository repository = new InMemoryCapabilityRepository();
        repository.upsert(capability("mcp.files.read_file", CapabilityType.MCP_TOOL,
                CapabilityExposureMode.DEFERRED, true, "读取 MCP 文件 read file filesystem"));
        repository.upsert(capability("skill.superpowers.tdd", CapabilityType.SKILL,
                CapabilityExposureMode.DEFERRED, true, "测试驱动开发 test driven development"));
        repository.upsert(capability("mcp.mail.send", CapabilityType.MCP_TOOL,
                CapabilityExposureMode.DISABLED, true, "发送邮件 send mail"));
        FallbackLexicalCapabilitySearchService service = new FallbackLexicalCapabilitySearchService(repository);

        CapabilitySearchResult result = service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "read filesystem", 5, true));

        assertThat(result.strategy()).isEqualTo("FALLBACK_LEXICAL");
        assertThat(result.results()).extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("mcp.files.read_file");
        assertThat(repository.events).hasSize(1);
        assertThat(repository.events.get(0).selectedCapabilityIdsJson()).contains("mcp.files.read_file");
        assertThat(repository.events.get(0).rejectedCapabilityIdsJson()).contains("mcp.mail.send");
    }

    private static CapabilityDescriptor capability(String id,
                                                   CapabilityType type,
                                                   CapabilityExposureMode mode,
                                                   boolean enabled,
                                                   String searchText) {
        return new CapabilityDescriptor(id, type, id.substring(0, id.indexOf('.')),
                id.substring(id.lastIndexOf('.') + 1), id, searchText, "test",
                "hash", searchText, mode, enabled, Instant.parse("2026-05-27T00:00:00Z"));
    }

    private static final class InMemoryCapabilityRepository implements CapabilityRepository {
        private final List<CapabilityDescriptor> capabilities = new ArrayList<>();
        private final List<CapabilitySearchEventRecord> events = new ArrayList<>();

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
                    .filter(descriptor -> descriptor.capabilityId().equals(capabilityId))
                    .findFirst();
        }

        @Override
        public void updateSettings(String capabilityId, Boolean enabled, CapabilityExposureMode exposureMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordSearchEvent(CapabilitySearchEventRecord record) {
            events.add(record);
        }

        @Override
        public List<String> recentSelectedCapabilityIds(String threadId, int limit) {
            return List.of();
        }
    }
}
