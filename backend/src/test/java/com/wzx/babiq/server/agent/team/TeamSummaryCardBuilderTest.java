package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队成员摘要卡构建测试。
 *
 * <p>摘要卡是 supervisor 和右侧团队面板看到的有界文本，不能额外调用模型，也不能把
 * 成员全文塞进时间线。</p>
 */
class TeamSummaryCardBuilderTest {

    @Test
    void buildCard_should_truncate_member_output_and_point_to_markdown_detail() {
        TeamSummaryCardBuilder builder = new TeamSummaryCardBuilder(new ApproximateContextTokenEstimator());
        String fullText = "第一段说明。\n第二段包含非常长的成员执行结果，需要被确定性截断，避免撑爆 supervisor 输入窗口。";

        String card = builder.buildCard("writer", 3, fullText, Path.of("rounds", "r3-writer.md"), 24);

        assertThat(card)
                .contains("writer")
                .contains("第 3 轮")
                .contains("第一段说明。 第二段包含非常长的")
                .contains("已截断")
                .contains("详情见 rounds/r3-writer.md");
        assertThat(card).doesNotContain("避免撑爆 supervisor 输入窗口");
    }

    @Test
    void buildCard_should_keep_short_output_without_truncation_marker() {
        TeamSummaryCardBuilder builder = new TeamSummaryCardBuilder(new ApproximateContextTokenEstimator());

        String card = builder.buildCard("explorer", 1, "已经读取目录并确认只有 index.html。", Path.of("rounds/r1-explorer.md"), 120);

        assertThat(card)
                .contains("explorer")
                .contains("已经读取目录并确认只有 index.html。")
                .contains("详情见 rounds/r1-explorer.md")
                .doesNotContain("已截断");
    }
}
