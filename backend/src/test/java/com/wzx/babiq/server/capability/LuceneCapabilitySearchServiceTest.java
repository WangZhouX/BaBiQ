package com.wzx.babiq.server.capability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lucene 能力搜索服务测试。
 *
 * <p>P3-5a 用 Spring AI Community 的 LuceneToolSearcher 替换自实现 fallback。
 * 这些用例固定 BaBiQ 自己关心的端口语义：只搜索启用且非 DISABLED 的能力、写入审计事件、
 * 支持 CJK 查询，并在能力目录变化后重建索引。</p>
 */
class LuceneCapabilitySearchServiceTest {

    /** 测试内存仓库，模拟 SQLite 能力事实源。 */
    private InMemoryCapabilityRepository repository;
    /** 被测 Lucene 搜索服务。 */
    private LuceneCapabilitySearchService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCapabilityRepository();
        repository.upsert(capability("local.read_file", "read_file", CapabilityType.LOCAL_TOOL,
                CapabilityExposureMode.DEFERRED, true, "读取文件 read file filesystem inspect content"));
        repository.upsert(capability("local.write_file", "write_file", CapabilityType.LOCAL_TOOL,
                CapabilityExposureMode.DEFERRED, true, "写入文件 write file filesystem create content"));
        repository.upsert(capability("local.exec_shell", "exec_shell", CapabilityType.LOCAL_TOOL,
                CapabilityExposureMode.DEFERRED, true, "执行命令 execute shell command terminal"));
        repository.upsert(capability("skill.hidden", "hidden", CapabilityType.SKILL,
                CapabilityExposureMode.DISABLED, true, "隐藏技能 hidden skill"));
        service = new LuceneCapabilitySearchService(repository);
        service.rebuildIndex();
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    @DisplayName("Lucene 搜索返回相关能力并写入 LUCENE 审计事件")
    void search_should_return_related_capabilities_and_record_lucene_event() {
        CapabilitySearchResult result = service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "read file", 5, true));

        assertThat(result.strategy()).isEqualTo("LUCENE");
        assertThat(result.results()).extracting(CapabilityDescriptor::capabilityId)
                .startsWith("local.read_file")
                .doesNotContain("skill.hidden");
        assertThat(repository.events).hasSize(1);
        CapabilitySearchEventRecord event = repository.events.getFirst();
        assertThat(event.strategy()).isEqualTo("LUCENE");
        assertThat(event.selectedCapabilityIdsJson()).contains("local.read_file");
        assertThat(event.rejectedCapabilityIdsJson()).contains("skill.hidden");
    }

    @Test
    @DisplayName("CJK 查询可以命中中文能力说明")
    void search_should_support_cjk_query_tokens() {
        CapabilitySearchResult result = service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "读取", 5, false));

        assertThat(result.results()).extracting(CapabilityDescriptor::capabilityId)
                .contains("local.read_file")
                .doesNotContain("local.write_file");
        assertThat(repository.events).isEmpty();
    }

    @Test
    @DisplayName("limit 参数限制返回数量")
    void search_should_honor_limit() {
        CapabilitySearchResult result = service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "file", 1, false));

        assertThat(result.results()).hasSize(1);
    }

    @Test
    @DisplayName("目录变化后重建索引，禁用能力不再可搜")
    void catalog_change_should_rebuild_index() {
        assertThat(service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "shell command", 5, false)).results())
                .extracting(CapabilityDescriptor::capabilityId)
                .contains("local.exec_shell");

        repository.updateSettings("local.exec_shell", true, CapabilityExposureMode.DISABLED);
        service.onCatalogChanged(new CapabilityCatalogChangedEvent(this));

        assertThat(service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "shell command", 5, false)).results())
                .extracting(CapabilityDescriptor::capabilityId)
                .doesNotContain("local.exec_shell");
    }

    @Test
    @DisplayName("无关 query 返回空结果")
    void search_should_return_empty_for_unknown_query() {
        CapabilitySearchResult result = service.search(new CapabilitySearchRequest(
                "thr_1", "turn_1", "zzzz-no-match", 5, false));

        assertThat(result.results()).isEmpty();
    }

    private static CapabilityDescriptor capability(String id,
                                                   String name,
                                                   CapabilityType type,
                                                   CapabilityExposureMode mode,
                                                   boolean enabled,
                                                   String searchText) {
        return new CapabilityDescriptor(id, type, "local",
                name, name, searchText, "test",
                "hash", searchText, mode, enabled, Instant.parse("2026-05-27T00:00:00Z"));
    }

    /**
     * 最小内存仓库，只实现 Lucene 搜索测试需要的能力目录行为。
     */
    private static final class InMemoryCapabilityRepository implements CapabilityRepository {
        /** 能力目录。 */
        private final List<CapabilityDescriptor> capabilities = new ArrayList<>();
        /** 搜索审计记录。 */
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
            CapabilityDescriptor current = findById(capabilityId).orElseThrow();
            upsert(new CapabilityDescriptor(
                    current.capabilityId(),
                    current.type(),
                    current.namespace(),
                    current.name(),
                    current.displayName(),
                    current.description(),
                    current.sourceId(),
                    current.schemaHash(),
                    current.searchText(),
                    exposureMode == null ? current.exposureMode() : exposureMode,
                    enabled == null ? current.enabled() : enabled,
                    current.lastSeenAt()));
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
