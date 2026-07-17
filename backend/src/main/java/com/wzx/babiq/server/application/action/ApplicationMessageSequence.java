package com.wzx.babiq.server.application.action;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 为同一 desktop session 生成严格递增的服务端应用协议消息序号。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationMessageSequence {

    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public long next(String desktopSessionId) {
        if (desktopSessionId == null || desktopSessionId.isBlank()) {
            throw new IllegalArgumentException("desktopSessionId must not be blank");
        }
        return sequences.computeIfAbsent(desktopSessionId, ignored -> new AtomicLong()).incrementAndGet();
    }
}
