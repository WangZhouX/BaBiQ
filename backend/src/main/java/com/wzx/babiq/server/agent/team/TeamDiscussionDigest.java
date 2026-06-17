package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.context.compaction.CompactionSource;
import com.wzx.babiq.server.context.compaction.CompactionSourceItem;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategy;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategyRequest;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategyResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队滚动讨论概要。
 *
 * <p>它复用 P3 的摘要策略端口生成有界 digest，但不写 P3 的上下文窗口和压缩审计表：
 * 团队 digest 是任务级 blackboard 产物，生命周期和主对话上下文不同。</p>
 */
@Component
public class TeamDiscussionDigest {

    /** P3 短期压缩策略端口。 */
    private final ContextCompactionStrategy compactionStrategy;
    /** token 粗估器。 */
    private final ContextTokenEstimator tokenEstimator;

    /**
     * 创建团队讨论概要服务。
     */
    public TeamDiscussionDigest(ContextCompactionStrategy compactionStrategy,
                                ContextTokenEstimator tokenEstimator) {
        this.compactionStrategy = compactionStrategy;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 把新成员摘要卡并入滚动概要；超预算时压缩旧概要，最近卡片保持可读。
     */
    public String roll(String currentDigest, String newCard, int budgetTokens) {
        String merged = append(currentDigest, newCard);
        if (budgetTokens <= 0 || tokenEstimator.estimate(merged) <= budgetTokens) {
            return merged;
        }
        String compacted = compact(currentDigest, newCard);
        String result = append(compacted, newCard);
        return tokenEstimator.estimate(result) <= budgetTokens ? result : trimToBudget(result, budgetTokens);
    }

    private String compact(String currentDigest, String newCard) {
        String sourceText = blankToDefault(currentDigest, "");
        if (sourceText.isBlank()) {
            return "";
        }
        ContextCompactionStrategyResult result = compactionStrategy.summarize(new ContextCompactionStrategyRequest(
                "team_digest",
                "team_digest",
                null,
                null,
                new CompactionSource(
                        List.of(new CompactionSourceItem("team_digest", "assistant", sourceText)),
                        "team_digest",
                        "team_digest",
                        "team_digest"),
                null,
                blankToDefault(newCard, "")));
        if (result == null || result.summary() == null || result.summary().isBlank()) {
            return deterministicTail(sourceText, 600);
        }
        return result.summary().trim();
    }

    private String trimToBudget(String value, int budgetTokens) {
        int maxChars = Math.max(1, budgetTokens * 3);
        return deterministicTail(value, maxChars);
    }

    private static String append(String currentDigest, String newCard) {
        String current = blankToDefault(currentDigest, "").trim();
        String card = blankToDefault(newCard, "").trim();
        if (current.isBlank()) {
            return card;
        }
        if (card.isBlank()) {
            return current;
        }
        return current + "\n\n## 最近更新\n" + card;
    }

    private static String deterministicTail(String value, int maxChars) {
        String text = blankToDefault(value, "").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return "..." + text.substring(text.length() - maxChars);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
