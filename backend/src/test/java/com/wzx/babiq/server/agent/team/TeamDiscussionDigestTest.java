package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategyRequest;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategyResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队滚动讨论概要测试。
 *
 * <p>团队 digest 是成员共享态势感知，不保存成员全文；超预算时复用 P3 压缩策略端口。</p>
 */
class TeamDiscussionDigestTest {

    @Test
    void roll_should_append_new_card_when_digest_is_under_budget() {
        TeamDiscussionDigest digest = new TeamDiscussionDigest(
                request -> {
                    throw new AssertionError("预算内不应该调用压缩策略");
                },
                new ApproximateContextTokenEstimator());

        String result = digest.roll("已有概要", "writer 完成修改", 200);

        assertThat(result)
                .contains("已有概要")
                .contains("writer 完成修改");
    }

    @Test
    void roll_should_use_p3_compaction_strategy_when_digest_exceeds_budget() {
        AtomicReference<ContextCompactionStrategyRequest> captured = new AtomicReference<>();
        TeamDiscussionDigest digest = new TeamDiscussionDigest(request -> {
            captured.set(request);
            return new ContextCompactionStrategyResult("压缩后的旧讨论概要");
        }, new ApproximateContextTokenEstimator());
        String current = "旧讨论内容 ".repeat(80);
        String newCard = "reviewer 复核通过";

        String result = digest.roll(current, newCard, 40);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().source().items())
                .singleElement()
                .satisfies(item -> assertThat(item.text()).contains("旧讨论内容"));
        assertThat(result)
                .contains("压缩后的旧讨论概要")
                .contains("reviewer 复核通过")
                .doesNotContain("旧讨论内容 旧讨论内容 旧讨论内容 旧讨论内容");
    }
}
