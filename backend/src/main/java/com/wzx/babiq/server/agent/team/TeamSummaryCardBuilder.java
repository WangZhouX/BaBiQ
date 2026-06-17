package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ContextTokenEstimator;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 团队成员摘要卡构建器。
 *
 * <p>摘要卡是给 supervisor 和右侧团队面板看的有界文本。它只做确定性截断，
 * 不额外调用模型，成员完整输出始终以 Markdown 产物保存。</p>
 */
@Component
public class TeamSummaryCardBuilder {

    /** 默认摘要正文长度。 */
    private static final int DEFAULT_MAX_CHARS = 600;

    /** token 粗估器，用于在摘要卡里暴露非计费统计。 */
    private final ContextTokenEstimator tokenEstimator;

    /**
     * 创建摘要卡构建器。
     */
    public TeamSummaryCardBuilder(ContextTokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 把成员完整输出转换成有界摘要卡。
     */
    public String buildCard(String member, int round, String fullText, Path detailRef, int maxChars) {
        String normalized = normalize(fullText);
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        boolean truncated = normalized.length() > limit;
        String body = truncated ? normalized.substring(0, limit) : normalized;
        String detail = detailRef == null ? "" : detailRef.toString().replace('\\', '/');
        return """
                成员：%s
                状态：completed
                轮次：第 %d 轮
                token 粗估：%d
                摘要：%s%s
                详情见 %s
                """.formatted(
                blankToDefault(member, "unknown"),
                Math.max(0, round),
                tokenEstimator.estimate(fullText),
                body,
                truncated ? " ...（已截断）" : "",
                detail).trim();
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
