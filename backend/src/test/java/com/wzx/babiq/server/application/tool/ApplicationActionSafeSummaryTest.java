package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Focused regression coverage for application-action display summaries. */
class ApplicationActionSafeSummaryTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void recursiveJsonRedactionPreservesSafeTextAndRedactsSensitiveTextualLeaves() {
        String nested = """
                {"message":"safe message 联系人 13800138000 身份证 330102199001011234",
                 "notes":["银行卡 6222021234567890", "Authorization: Bearer scalar-secret-token"],
                 "account":{"password":"pw-secret","id_card":"330102199001011234"},
                 "rows":[{"mobile":"13800138000","bank-card":"6222021234567890"}],
                 "authorization":"Bearer abcdefghijklmnop","refreshToken":"refresh-secret"
                }
                """;
        ApplicationActionSafeSummary summary =
                new ApplicationActionSafeSummary(json, new MemorySecretRedactor(), 240);

        assertThat(summary.sanitize(nested))
                .contains("safe message", "[REDACTED]")
                .doesNotContain("pw-secret", "330102199001011234", "13800138000",
                        "6222021234567890", "scalar-secret-token", "abcdefghijklmnop",
                        "refresh-secret");
    }

    @Test
    void plainTextCredentialsConsumeBracketedQuotedAndBearerValuesCompletely() {
        ApplicationActionSafeSummary summary =
                new ApplicationActionSafeSummary(json, new MemorySecretRedactor(), 240);
        String raw = "safe prefix token=[abcdefgh123456] secret: [abc123] "
                + "Authorization: Bearer abcdefghijkl api_key=\"quoted-secret\" safe suffix";

        assertThat(summary.sanitize(raw))
                .contains("safe prefix", "safe suffix", "[REDACTED]")
                .doesNotContain("abcdefgh123456", "abc123", "abcdefghijkl", "quoted-secret", "Bearer");
    }

    @Test
    void overlongCredentialsNeverLeaveASecretTail() {
        ApplicationActionSafeSummary summary =
                new ApplicationActionSafeSummary(json, new MemorySecretRedactor(), 240);
        String secret = "secret-tail-" + "z".repeat(1_000);

        for (String raw : java.util.List.of(
                "Authorization: Bearer " + secret + " safe suffix",
                "token=[" + secret + "] safe suffix",
                "secret=\"" + secret + "\" safe suffix")) {
            assertThat(summary.sanitize(raw))
                    .contains("[REDACTED]")
                    .doesNotContain("secret-tail", "zzzzzzzzzz")
                    .hasSizeLessThanOrEqualTo(240);
        }
    }
}
