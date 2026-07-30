package com.wzx.babiq.server.business.upload;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MultipartException;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessUploadExceptionHandlerTest {
    private final BusinessUploadExceptionHandler handler = new BusinessUploadExceptionHandler();

    @Test
    void parser_missing_header_and_catch_all_use_fixed_redacted_error_schema() {
        var multipart = handler.malformed(new MultipartException("path=C:\\secret ticket=canary"));
        var missing = handler.malformed((MissingRequestHeaderException) null);
        var fallback = handler.fallback(new RuntimeException("oa-file-id-canary"));

        assertThat(multipart.getBody().businessCode()).isEqualTo("BUSINESS_ATTACHMENT_REJECTED");
        assertThat(missing.getBody().businessCode()).isEqualTo("BUSINESS_ATTACHMENT_REJECTED");
        assertThat(fallback.getBody().businessCode()).isEqualTo("BUSINESS_REMOTE_UNAVAILABLE");
        assertThat(multipart.getBody().toString()).doesNotContain("secret", "canary", "path");
    }
}
