package com.wzx.babiq.server.conversation;

import com.wzx.babiq.server.api.dto.ThreadLoadResult;
import com.wzx.babiq.server.api.dto.ThreadListResult;
import com.wzx.babiq.server.api.dto.RuntimeItemRemoveResult;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.TeamItem;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P2-2 会话历史集成测试。
 *
 * <p>这里使用独立 SQLite 文件验证“运行期写库 -> 重新从应用服务读取”的闭环，避免污染用户真实
 * `${user.home}/.babiq/babiq.db`。</p>
 */
@SpringBootTest
class ConversationHistoryIT {

    /** 每次测试运行使用独立数据库文件，确保历史恢复断言没有旧数据干扰。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "conversation-history-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 把 Spring Boot 测试上下文指向临时 SQLite 文件。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @TestConfiguration
    static class MockAgentConfig {

        @Bean
        @Primary
        ReActStrategy reActStrategy() {
            return mock(ReActStrategy.class);
        }
    }

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConversationEventRecorder recorder;
    @Autowired
    private ConversationApplicationService applicationService;
    @Autowired
    private ConversationRepository repository;

    @Test
    void history_should_survive_memory_index_rebuild() {
        Thread thread = conversationService.createThread("E:\\BaBiQ");
        Turn turn = conversationService.startTurn(thread.id(), "你好", "deepseek", "deepseek-v4-pro",
                "E:\\BaBiQ", "DANGER_FULL_ACCESS", "ON_REQUEST");

        recorder.recordItemAdded(thread.id(), turn.id(), new UserMessageItem("it_user", "userMessage", "你好"));
        recorder.recordItemAdded(thread.id(), turn.id(), AgentMessageItem.full("it_agent", "你好，有什么可以帮忙？"));
        recorder.recordTurnSummary(thread.id(), turn.id(), new TurnSummaryItem(
                "it_summary", "turnSummary", "completed", "deepseek-v4-pro",
                10, 20, 30, 0, 900));
        recorder.recordTurnFinished(turn.id(), "COMPLETED", null);

        // 重新构造应用服务，模拟内存索引丢失后只从 SQLite 读取历史。
        ConversationApplicationService reloadedService = new ConversationApplicationService(repository, conversationService);
        ThreadListResult listResult = reloadedService.listThreads("E:\\BaBiQ", false, 30, null);
        ThreadLoadResult loadResult = reloadedService.loadThread(thread.id(), 200, null);

        assertThat(listResult.threads()).extracting("threadId").contains(thread.id());
        assertThat(loadResult.items()).hasSize(3);
        assertThat(loadResult.items().get(0).get("type").asText()).isEqualTo("userMessage");
        assertThat(loadResult.latestSummary()).isNotNull();
    }

    @Test
    void removed_team_runtime_item_should_not_reload_from_history() {
        Thread thread = conversationService.createThread("H:\\aaa");
        Turn turn = conversationService.startTurn(thread.id(), "run team", "deepseek", "deepseek-v4-pro",
                "H:\\aaa", "DANGER_FULL_ACCESS", "ON_REQUEST");
        TeamItem team = new TeamItem(
                "it_team_1",
                "team",
                "team_1",
                "P6-2 Flow Smoke Test",
                "failed",
                "Resume request without a valid checkpoint!",
                true,
                true,
                null,
                1,
                5,
                List.of(new TeamItem.MemberStatus(
                        "member_explorer",
                        "explorer",
                        "Explorer",
                        "failed",
                        "READ_ONLY_TOOL",
                        "read index.html",
                        0,
                        0,
                        "checkpoint failed"
                )));
        recorder.recordItemAdded(thread.id(), turn.id(), team);

        assertThat(applicationService.loadThread(thread.id(), 200, null).items())
                .extracting(item -> item.path("type").asText())
                .contains("team");

        RuntimeItemRemoveResult removed = applicationService.removeRuntimeItem("it_team_1", "team");

        assertThat(removed).isEqualTo(new RuntimeItemRemoveResult("it_team_1", "team", "removed", true));
        assertThat(repository.findItem("it_team_1")).get()
                .extracting("status")
                .isEqualTo("removed");
        assertThat(applicationService.loadThread(thread.id(), 200, null).items())
                .extracting(item -> item.path("type").asText())
                .doesNotContain("team");
    }
}
