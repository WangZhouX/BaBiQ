package com.wzx.babiq.server.agent.flow;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class FlowConcurrencyAttributionTest {

    @Test
    void delegation_context_should_count_parallel_child_tool_calls_without_lost_updates() throws Exception {
        TurnObservationContext observation = TurnObservationContext.start("thr_parallel", "turn_parallel", "deepseek", "deepseek-v4-pro");
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_parallel",
                "dlg_parallel",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                "parallel_worker",
                BabiqAgentMode.WORKSPACE_TOOL,
                null,
                observation);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 25; j++) {
                        delegation.recordChildToolCall("read_file");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(delegation.toolCallCount()).isEqualTo(400);
        assertThat(delegation.parentAgent()).isEqualTo(BuiltInSubAgents.MAIN_AGENT_NAME);
        assertThat(delegation.childAgent()).isEqualTo("parallel_worker");
    }
}
