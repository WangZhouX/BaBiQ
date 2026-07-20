package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider 多模态拒绝的稳定分类测试。
 */
class AttachmentModelFailureClassifierTest {

    private final AttachmentModelFailureClassifier classifier =
            new AttachmentModelFailureClassifier();

    @Test
    void classifies_known_image_rejection_only_when_media_was_sent() {
        WebClientResponseException rejection = response(
                400, "{\"error\":{\"message\":\"This model does not support image_url input\"}}");

        assertThat(classifier.classify(rejection, true))
                .get()
                .satisfies(failure -> {
                    assertThat(failure.code())
                            .isEqualTo(AttachmentErrorCode.ATTACHMENT_MODEL_UNSUPPORTED);
                    assertThat(failure.safeMessage())
                            .doesNotContain("image_url", "400", "error");
                });
        assertThat(classifier.classify(rejection, false)).isEmpty();
    }

    @Test
    void classifies_unsupported_media_status_without_exposing_response_body() {
        String remoteSecret = "unsupported media type; upstream-secret-token";

        assertThat(classifier.classify(response(415, remoteSecret), true))
                .get()
                .satisfies(failure -> assertThat(failure.safeMessage())
                        .doesNotContain(remoteSecret, "upstream-secret-token"));
    }

    @Test
    void leaves_unrelated_provider_and_local_failures_unclassified() {
        assertThat(classifier.classify(response(429, "rate limit exceeded"), true)).isEmpty();
        assertThat(classifier.classify(
                new IllegalStateException("socket failed at C:\\private\\screen.png"), true))
                .isEmpty();
    }

    private static WebClientResponseException response(int status, String body) {
        return WebClientResponseException.create(
                status,
                "Provider rejected request",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
