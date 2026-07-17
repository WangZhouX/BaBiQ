package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.action.ApplicationActionRedactor;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;

/** 将桌面返回的展示摘要压缩为有界、结构化脱敏文本。 */
final class ApplicationActionSafeSummary {
    private final ApplicationActionRedactor redactor;
    private final int limit;

    ApplicationActionSafeSummary(ObjectMapper json, MemorySecretRedactor secretRedactor, int limit) {
        this.redactor = new ApplicationActionRedactor(json, secretRedactor);
        this.limit = limit;
    }

    String sanitize(String value) {
        return redactor.sanitize(value, limit);
    }
}
