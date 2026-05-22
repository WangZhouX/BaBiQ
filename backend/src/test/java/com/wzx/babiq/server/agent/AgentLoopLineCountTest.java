package com.wzx.babiq.server.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D21 行数硬约束测试。
 *
 * <p>AgentLoop 应保持为薄编排层，横切逻辑进入 Hook/Interceptor。</p>
 */
class AgentLoopLineCountTest {

    @Test
    void agent_loop_file_should_stay_compact() throws Exception {
        Path file = Path.of("src/main/java/com/wzx/babiq/server/agent/AgentLoop.java");

        long lines;
        try (var stream = Files.lines(file)) {
            lines = stream.count();
        }

        assertThat(lines).isLessThanOrEqualTo(100);
    }
}
