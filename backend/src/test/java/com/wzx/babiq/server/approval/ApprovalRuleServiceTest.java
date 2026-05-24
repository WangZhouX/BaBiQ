package com.wzx.babiq.server.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Always 审批规则服务测试。
 *
 * <p>P2-3 只允许 session scope 的“始终允许”：它必须绑定 thread、tool 和参数指纹，
 * 不能变成全局永久放行。</p>
 */
@SpringBootTest
class ApprovalRuleServiceTest {

    /** 独立 SQLite 文件，确保 always 规则不会污染真实会话。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "approval-rules-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void settingsProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ApprovalRuleService approvalRuleService;

    @Test
    @DisplayName("同一 thread、tool、参数指纹命中 always 规则时自动放行")
    void always_rule_should_match_same_thread_tool_and_args() {
        approvalRuleService.rememberAlways("thr_1", "write_file", "{\"path\":\"a.txt\"}", "session");

        assertThat(approvalRuleService.isAlwaysAllowed("thr_1", "write_file", "{\"path\":\"a.txt\"}"))
                .isTrue();
        assertThat(approvalRuleService.isAlwaysAllowed("thr_1", "write_file", "{\"path\":\"b.txt\"}"))
                .isFalse();
        assertThat(approvalRuleService.isAlwaysAllowed("thr_2", "write_file", "{\"path\":\"a.txt\"}"))
                .isFalse();
    }

    @Test
    @DisplayName("session scope 结束后 always 规则不再生效")
    void always_rule_should_expire_when_session_scope_is_cleared() {
        approvalRuleService.rememberAlways("thr_1", "exec_shell", "{\"command\":\"git status\"}", "session");

        approvalRuleService.expireSession("thr_1");

        assertThat(approvalRuleService.isAlwaysAllowed("thr_1", "exec_shell", "{\"command\":\"git status\"}"))
                .isFalse();
    }
}
