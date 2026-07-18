package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PendingApprovals 测试。
 *
 * <p>验证 D23 HITL 中断元数据可以按 threadId 短期缓存并在 approval/respond 时一次性取出。</p>
 */
class PendingApprovalsTest {

    @Test
    void put_peek_take_and_remove() {
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata metadata = mock(InterruptionMetadata.class);

        pendingApprovals.put("thr_1", metadata);

        assertThat(pendingApprovals.peek("thr_1")).isSameAs(metadata);
        assertThat(pendingApprovals.take("thr_1")).isSameAs(metadata);
        assertThat(pendingApprovals.take("thr_1")).isNull();
    }

    @Test
    void remove_clears_cached_metadata() {
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata metadata = mock(InterruptionMetadata.class);

        pendingApprovals.put("thr_1", metadata);
        pendingApprovals.remove("thr_1");

        assertThat(pendingApprovals.peek("thr_1")).isNull();
    }

    @Test
    void claimOnlyConsumesTheExactPeekedMetadata() {
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata original = mock(InterruptionMetadata.class);
        InterruptionMetadata replacement = mock(InterruptionMetadata.class);
        pendingApprovals.put("thr_1", original);

        assertThat(pendingApprovals.claim("thr_1", replacement)).isNull();
        assertThat(pendingApprovals.peek("thr_1")).isSameAs(original);
        assertThat(pendingApprovals.claim("thr_1", original)).isSameAs(original);
        assertThat(pendingApprovals.peek("thr_1")).isNull();
    }

    @Test
    void removeExactNeverDeletesAReplacementApproval() {
        PendingApprovals pendingApprovals = new PendingApprovals();
        InterruptionMetadata original = mock(InterruptionMetadata.class);
        InterruptionMetadata replacement = mock(InterruptionMetadata.class);
        pendingApprovals.put("thr_1", original);
        pendingApprovals.put("thr_1", replacement);

        assertThat(pendingApprovals.removeExact("thr_1", original)).isFalse();
        assertThat(pendingApprovals.peek("thr_1")).isSameAs(replacement);
        assertThat(pendingApprovals.removeExact("thr_1", replacement)).isTrue();
        assertThat(pendingApprovals.peek("thr_1")).isNull();
    }
}
