package com.wzx.babiq.server.application.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.protocol.ApplicationCatalogMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 保存与当前目录 epoch 对齐、按 contextSequence 严格递增的页面上下文快照。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class ApplicationPageContextRegistry {

    private final ApplicationIdentityRegistry identities;
    private final ApplicationCatalogRegistry catalogs;
    private final Map<String, PageContextSnapshot> snapshots = new HashMap<>();

    public ApplicationPageContextRegistry(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    /** 发布页面上下文；校验全部完成后才推进 contextSequence 水位。 */
    public synchronized PageContextSnapshot publish(
            TrustedDesktopConnection connection,
            ApplicationCatalogMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        ApplicationProtocolValidator.validate(message);
        TrustedBusinessIdentity identity = requireMatchingIdentity(connection, message);
        ApplicationCatalogRegistry.CatalogSnapshot catalog = catalogs.current(connection)
                .orElseThrow(() -> new IllegalStateException("Current catalog is required"));
        if (message.catalogEpoch() != catalog.catalogEpoch()) {
            throw new IllegalArgumentException("Context catalogEpoch must equal current catalog epoch");
        }

        JsonNode payload = requirePayload(message);
        ApplicationCatalogRegistry.validateOptionalPayloadScope(payload, identity);
        PageContextSnapshot current = snapshots.get(connection.webSocketSessionId());
        if (current != null) {
            if (!current.connection().equals(connection)) {
                throw new IllegalStateException("Page context belongs to another connection");
            }
            if (message.contextSequence() <= current.contextSequence()) {
                throw new IllegalArgumentException("contextSequence must strictly increase");
            }
        }

        PageContextSnapshot next = new PageContextSnapshot(
                connection, message.catalogEpoch(), message.contextSequence(), payload, true);
        snapshots.put(connection.webSocketSessionId(), next);
        return next;
    }

    /** 返回匹配连接的当前页面上下文。 */
    public synchronized Optional<PageContextSnapshot> current(TrustedDesktopConnection connection) {
        PageContextSnapshot snapshot = snapshots.get(connection.webSocketSessionId());
        return snapshot != null && snapshot.connection().equals(connection)
                ? Optional.of(snapshot)
                : Optional.empty();
    }

    /** 连接关闭或身份变化后清除上下文和 sequence 水位。 */
    public synchronized void clear(TrustedDesktopConnection connection) {
        PageContextSnapshot current = snapshots.get(connection.webSocketSessionId());
        if (current != null && current.connection().equals(connection)) {
            snapshots.remove(connection.webSocketSessionId());
        }
    }

    private TrustedBusinessIdentity requireMatchingIdentity(
            TrustedDesktopConnection connection,
            ApplicationCatalogMessage message) {
        TrustedBusinessIdentity identity = identities.current(connection)
                .orElseThrow(() -> new IllegalStateException("Authenticated identity is required"));
        if (!identity.desktopInstanceId().equals(message.desktopInstanceId())
                || !identity.desktopSessionId().equals(message.desktopSessionId())
                || !identity.authSessionId().equals(message.authSessionId())
                || identity.identityEpoch() != message.identityEpoch()
                || !identity.userId().equals(message.userId())
                || !identity.tenantId().equals(message.tenantId())
                || !identity.platformId().equals(message.platformId())) {
            throw new IllegalArgumentException("Context identity scope does not match trusted identity");
        }
        return identity;
    }

    private JsonNode requirePayload(ApplicationCatalogMessage message) {
        JsonNode payload = message.payload();
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Context payload must be an object");
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (message.payloadSize() != bytes.length) {
            throw new IllegalArgumentException("Context payloadSize does not match actual UTF-8 bytes");
        }
        ApplicationProtocolValidator.validateContextPayloadSize(bytes);
        return payload;
    }

    /** 页面上下文只作为不可信业务事实展示，不作为服务端指令执行。 */
    public record PageContextSnapshot(
            TrustedDesktopConnection connection,
            long catalogEpoch,
            long contextSequence,
            JsonNode payload,
            boolean untrustedData) {
        public PageContextSnapshot {
            payload = payload == null ? null : payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload == null ? null : payload.deepCopy();
        }
    }
}
