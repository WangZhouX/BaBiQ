package com.wzx.babiq.server.persistence;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzx.babiq.server.conversation.repository.AppSettingRecord;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import com.wzx.babiq.server.persistence.mapper.ThreadMapper;
import com.wzx.babiq.server.persistence.service.AppSettingPersistenceService;
import com.wzx.babiq.server.persistence.service.ProviderPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-1 repository adapter 集成测试。
 *
 * <p>测试同时覆盖 MyBatis-Plus mapper 的分页能力，以及面向领域 repository 的
 * 插入、查询、归档和安全边界。业务层后续只应该依赖 repository，而不是直接依赖 mapper。</p>
 */
@SpringBootTest
class RepositoryAdapterIT {

    /** 为每次测试运行准备独立 SQLite 文件，保证 repository 测试没有历史脏数据。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "repository-adapter-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 测试上下文使用独立数据库。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ThreadMapper threadMapper;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ProviderPersistenceService providerPersistenceService;
    @Autowired
    private AppSettingPersistenceService appSettingPersistenceService;

    @Test
    @DisplayName("MyBatis-Plus mapper 能插入并分页查询 thread")
    void mapper_should_insert_and_page_threads() {
        ThreadEntity entity = ThreadEntity.active(
                "thr_mapper",
                "Mapper 测试",
                "E:\\BaBiQ",
                "deepseek",
                "deepseek-v4-pro",
                "DANGER_FULL_ACCESS",
                "ON_REQUEST",
                Instant.now());

        threadMapper.insert(entity);
        Page<ThreadEntity> page = threadMapper.selectPage(Page.of(1, 20), null);

        assertThat(page.getRecords())
                .extracting(ThreadEntity::getThreadId)
                .contains("thr_mapper");
    }

    @Test
    @DisplayName("repository adapter 能保存 thread、turn、item 和 turnSummary")
    void repository_should_save_conversation_records() {
        Instant now = Instant.now();

        conversationRepository.createThread("thr_repo", "仓库测试", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                "turn_repo", "thr_repo", "RUNNING", "你好", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now));
        conversationRepository.saveItem(ItemRecord.of(
                "it_user", "thr_repo", "turn_repo", "userMessage", 1,
                "{\"id\":\"it_user\",\"type\":\"userMessage\",\"text\":\"你好\"}", "completed", now));
        conversationRepository.saveTurnSummary(TurnSummaryRecord.of(
                "turn_repo", 10, 20, 30, 1200, 1, now));

        assertThat(conversationRepository.findThread("thr_repo")).isPresent();
        assertThat(conversationRepository.listRecentThreads("E:\\BaBiQ", false, 10))
                .extracting(ThreadEntity::getThreadId)
                .contains("thr_repo");
        assertThat(conversationRepository.listItems("thr_repo", 100))
                .extracting(ItemRecord::itemId)
                .containsExactly("it_user");
        assertThat(conversationRepository.findTurnSummary("turn_repo"))
                .get()
                .satisfies(summary -> {
                    assertThat(summary.totalTokens()).isEqualTo(30L);
                    assertThat(summary.toolCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("归档 thread 后默认最近列表不再返回")
    void repository_should_hide_archived_threads_from_default_recent_list() {
        Instant now = Instant.now();
        conversationRepository.createThread("thr_archive", "归档测试", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "DANGER_FULL_ACCESS", "ON_REQUEST", now);

        conversationRepository.archiveThread("thr_archive", now.plusSeconds(1));

        assertThat(conversationRepository.listRecentThreads("E:\\BaBiQ", false, 10))
                .extracting(ThreadEntity::getThreadId)
                .doesNotContain("thr_archive");
        assertThat(conversationRepository.listRecentThreads("E:\\BaBiQ", true, 10))
                .extracting(ThreadEntity::getThreadId)
                .contains("thr_archive");
    }

    @Test
    @DisplayName("provider 配置只保存 secretRef，不保存明文 API Key")
    void provider_config_should_store_secret_ref_only() {
        ProviderConfigRecord record = ProviderConfigRecord.of(
                "provider_test",
                "测试 Provider",
                "OPENAI_COMPATIBLE",
                "https://example.com/v1",
                "test-model",
                "secret://provider_test",
                128000,
                true,
                Instant.now());

        providerPersistenceService.saveProvider(record);

        ProviderConfigRecord saved = providerPersistenceService.findProvider("provider_test").orElseThrow();
        assertThat(saved.secretRef()).isEqualTo("secret://provider_test");
        assertThat(saved.toString()).doesNotContain("sk-");
    }

    @Test
    @DisplayName("app setting 能按 key 保存和读取")
    void app_setting_should_round_trip() {
        AppSettingRecord record = new AppSettingRecord(
                "sandbox.mode",
                "DANGER_FULL_ACCESS",
                "string",
                Instant.now());

        appSettingPersistenceService.save(record);

        assertThat(appSettingPersistenceService.findByKey("sandbox.mode"))
                .get()
                .extracting(AppSettingRecord::settingValue)
                .isEqualTo("DANGER_FULL_ACCESS");
    }
}
