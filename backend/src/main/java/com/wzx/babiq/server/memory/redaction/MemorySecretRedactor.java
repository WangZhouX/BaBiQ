package com.wzx.babiq.server.memory.redaction;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆密钥脱敏器。
 *
 * <p>它只做确定性文本规则，不调用模型。这样即使 Phase1 抽取模型失误，
 * 入库前也有一层 Java 侧硬防线。规则故意偏保守：三次以上命中或私钥命中会标记 SECRET_RISK。</p>
 */
@Component
public class MemorySecretRedactor {

    /** Bearer token、api_key、token 等常见凭据写法。 */
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(api[_-]?key\\s*=\\s*|token\\s*=\\s*|Authorization\\s*:\\s*Bearer\\s+)([A-Za-z0-9._\\-]{8,})");

    /** OpenAI/兼容供应商常见 sk- 前缀密钥。 */
    private static final Pattern SK_SECRET = Pattern.compile("(?i)sk-[A-Za-z0-9._\\-]{8,}");

    /** PEM 私钥块。 */
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    /**
     * 对文本做脱敏并返回污染判断。
     *
     * @param text 待进入长期记忆候选的文本
     * @return 脱敏后的文本和风险状态
     */
    public MemorySecretRedactionResult redact(String text) {
        if (text == null || text.isBlank()) {
            return new MemorySecretRedactionResult("", 0, false, MemoryPollutionStatus.CLEAN);
        }
        Redaction privateKeyRedaction = replaceAll(PRIVATE_KEY, text, "[REDACTED:private-key]");
        Redaction inlineRedaction = replaceInlineSecrets(privateKeyRedaction.text());
        Redaction skRedaction = replaceAll(SK_SECRET, inlineRedaction.text(), "[REDACTED:api-key]");
        int redactionCount = privateKeyRedaction.count() + inlineRedaction.count() + skRedaction.count();
        boolean privateKeyHit = privateKeyRedaction.count() > 0;
        MemoryPollutionStatus status = privateKeyHit || redactionCount >= 3
                ? MemoryPollutionStatus.SECRET_RISK
                : MemoryPollutionStatus.CLEAN;
        return new MemorySecretRedactionResult(skRedaction.text(), redactionCount, privateKeyHit, status);
    }

    private static Redaction replaceInlineSecrets(String text) {
        Matcher matcher = INLINE_SECRET.matcher(text);
        StringBuilder builder = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(matcher.group(1) + "[REDACTED:api-key]"));
            count++;
        }
        matcher.appendTail(builder);
        return new Redaction(builder.toString(), count);
    }

    private static Redaction replaceAll(Pattern pattern, String text, String replacement) {
        Matcher matcher = pattern.matcher(text);
        String result = matcher.replaceAll(match -> replacement);
        int count = 0;
        matcher.reset();
        while (matcher.find()) {
            count++;
        }
        return new Redaction(result, count);
    }

    /**
     * 内部替换结果，避免同一文本在多条规则之间丢失命中次数。
     */
    private record Redaction(String text, int count) {
    }
}
