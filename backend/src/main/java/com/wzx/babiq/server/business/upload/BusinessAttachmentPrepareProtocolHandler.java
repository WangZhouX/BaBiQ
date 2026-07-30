package com.wzx.babiq.server.business.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.wzx.babiq.server.api.JsonRpcMultiMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** JSON-RPC preparation boundary for the authenticated loopback multipart upload. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessAttachmentPrepareProtocolHandler implements JsonRpcMultiMethodHandler {
    public static final String METHOD = "business/attachments/upload/prepare";
    private static final Set<String> ALLOWED_FIELDS = Set.of("operation", "clientOperationId",
            "scope", "teamId", "typeId", "parentRelationType", "parentResourceId", "parentRecordId",
            "formRevision", "files");

    private final BusinessAttachmentTicketService tickets;
    private final BusinessDesktopConnectionResolver connections;
    private final BusinessOaSessionRegistry sessions;
    private final ApplicationIdentityRegistry identities;
    private final BusinessScheduleService schedules;
    private final ObjectMapper mapper;

    public BusinessAttachmentPrepareProtocolHandler(BusinessAttachmentTicketService tickets,
                                                     BusinessDesktopConnectionResolver connections,
                                                     BusinessOaSessionRegistry sessions,
                                                     ApplicationIdentityRegistry identities,
                                                     BusinessScheduleService schedules,
                                                     ObjectMapper mapper) {
        this.tickets = tickets;
        this.connections = connections;
        this.sessions = sessions;
        this.identities = identities;
        this.schedules = schedules;
        this.mapper = mapper;
    }

    @Override public Set<String> methods() { return Set.of(METHOD); }

    @Override
    public Object handle(String method, JsonNode params, WebSocketSession session) {
        if (!METHOD.equals(method)) throw invalid();
        try {
            TrustedDesktopConnection connection = connections.requireFinalized(session);
            ReadyOaSessionLease lease = sessions.captureReady(connection);
            TrustedBusinessIdentity identity = identities.current(connection)
                    .orElseThrow(() -> new IllegalStateException("business identity is not READY"));
            if (!identity.authSessionId().equals(lease.authSessionId())
                    || !identity.userId().equals(lease.userId())
                    || !identity.tenantId().equals(lease.tenantId())
                    || !identity.platformId().equals(lease.platformId())
                    || identity.identityEpoch() <= 0) {
                throw new IllegalStateException("business identity does not match the active OA lease");
            }
            JsonNode input = params == null || params.isNull() ? mapper.createObjectNode() : params;
            requireObject(input);
            input.fieldNames().forEachRemaining(field -> { if (!ALLOWED_FIELDS.contains(field)) throw invalid(); });
            String operation = requiredText(input, "operation");
            String clientOperationId = requiredText(input, "clientOperationId");
            String scope = requiredText(input, "scope");
            String teamId = optionalText(input, "teamId");
            String typeId = requiredText(input, "typeId");
            String parentRelationType = requiredText(input, "parentRelationType");
            String parentResourceId = requiredText(input, "parentResourceId");
            String parentRecordId = optionalText(input, "parentRecordId");
            long formRevision = requiredNonNegativeLong(input, "formRevision");
            ArrayNode files = input.path("files").isArray() ? (ArrayNode) input.path("files") : null;
            if (files == null) throw invalid();
            ArrayList<BusinessAttachmentTicketService.FileDeclaration> declarations = new ArrayList<>();
            for (JsonNode file : files) {
                if (file == null || !file.isObject()) throw invalid();
                file.fieldNames().forEachRemaining(field -> {
                    if (!Set.of("fileName", "sizeBytes", "mediaType", "sha256").contains(field)) throw invalid();
                });
                declarations.add(new BusinessAttachmentTicketService.FileDeclaration(
                        requiredText(file, "fileName"), requiredPositiveLong(file, "sizeBytes"),
                        requiredText(file, "mediaType"), optionalText(file, "sha256")));
            }
            schedules.authorizeAttachmentPrepare(lease, identity, scope, teamId, typeId,
                    parentRelationType, parentResourceId, parentRecordId, formRevision);
            BusinessAttachmentTicketService.PreparedBatch batch = tickets.prepare(
                    connection, lease, operation, clientOperationId, identity.userId(), scope, teamId,
                    typeId, parentRelationType, parentResourceId, parentRecordId,
                    Long.toString(formRevision), declarations);
            return new PrepareResponse(batch.batchId(), batch.ticket(), batch.expiresAt().toString(), identity.identityEpoch(), lease.generation());
        } catch (JsonRpcException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR, "Internal server error",
                    Map.of("businessCode", "BUSINESS_ATTACHMENT_UNAVAILABLE"));
        }
    }

    @Override public String method() { return METHOD; }
    @Override public Object handle(JsonNode params, WebSocketSession session) { return handle(METHOD, params, session); }

    private static void requireObject(JsonNode input) { if (input == null || !input.isObject()) throw invalid(); }
    private static String requiredText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue().strip();
    }
    private static String optionalText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue().strip();
    }
    private static long requiredPositiveLong(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || value.longValue() <= 0) throw invalid();
        return value.longValue();
    }
    private static long requiredNonNegativeLong(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }
    private static JsonRpcException invalid() { return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Invalid attachment parameters"); }

    public record PrepareResponse(String attachmentBatchId, String ticket, String expiresAt,
                                  long identityEpoch, long generation) {
        @Override public String toString() {
            return "PrepareResponse(attachmentBatchId=[REDACTED], ticket=[REDACTED], expiresAt=" + expiresAt
                    + ", identityEpoch=" + identityEpoch + ", generation=" + generation + ")";
        }
    }
}
