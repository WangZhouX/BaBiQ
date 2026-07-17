package com.wzx.babiq.server.application.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationProtocolFixtureTest {

    private static final List<FixtureCase> FIXTURES = List.of(
            request("catalog-register.json", "application/catalog/register", ApplicationCatalogMessage.class),
            notification("catalog-update.json", "application/catalog/update", ApplicationCatalogMessage.class),
            notification("context-publish.json", "application/context/publish", ApplicationCatalogMessage.class),
            request("identity-bind.json", "application/identity/bind", ApplicationIdentityMessage.class),
            notification("identity-update.json", "application/identity/update", ApplicationIdentityMessage.class),
            request("action-request.json", "application/action/request", ApplicationActionMessage.class),
            notification("action-cancel.json", "application/action/cancel", ApplicationActionMessage.class),
            notification("action-accepted.json", "application/action/accepted", ApplicationActionMessage.class),
            notification("action-previewed.json", "application/action/previewed", ApplicationActionMessage.class),
            notification("action-approval-required.json", "application/action/approval-required", ApplicationActionMessage.class),
            notification("action-running.json", "application/action/running", ApplicationActionMessage.class),
            notification("action-completed.json", "application/action/completed", ApplicationActionMessage.class),
            notification("action-failed.json", "application/action/failed", ApplicationActionMessage.class),
            notification("action-rejected.json", "application/action/rejected", ApplicationActionMessage.class),
            notification("action-canceled.json", "application/action/canceled", ApplicationActionMessage.class),
            notification("action-expired.json", "application/action/expired", ApplicationActionMessage.class),
            notification("action-outcome-unknown.json", "application/action/outcome-unknown", ApplicationActionMessage.class),
            request("action-status.json", "application/action/status", ApplicationActionMessage.class),
            request("action-result-get.json", "application/action/result/get", ApplicationActionMessage.class),
            response("action-status-result.json", ApplicationProtocol.SuccessResponse.class),
            response("action-result-get-result.json", ApplicationProtocol.SuccessResponse.class),
            response("protocol-error.json", ApplicationProtocol.ErrorResponse.class)
    );

    @Test
    void canonicalFixturesRoundTripThroughMethodAwareJavaRecords() throws IOException {
        Path contractDirectory = repositoryRoot()
                .resolve("docs/superpowers/contracts/huitai-business-desktop-agent");
        Set<String> actualFiles;
        try (var files = Files.list(contractDirectory)) {
            actualFiles = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }

        assertEquals(22, FIXTURES.size());
        assertEquals(FIXTURES.stream().map(FixtureCase::fileName).collect(Collectors.toSet()), actualFiles);

        for (FixtureCase fixture : FIXTURES) {
            JsonNode canonical = ApplicationProtocol.readTree(
                    Files.readString(contractDirectory.resolve(fixture.fileName())));
            ObjectNode withUnknownFields = canonical.deepCopy();
            withUnknownFields.putObject("futureTopLevelField").put("enabled", true);
            if (withUnknownFields.path("params") instanceof ObjectNode params) {
                params.putObject("futureEnvelopeField").put("version", 2);
                if (params.path("payload") instanceof ObjectNode payload) {
                    payload.putObject("futurePayloadField").put("version", 2);
                }
            }
            if (withUnknownFields.path("result") instanceof ObjectNode result) {
                result.putObject("futureResultField").put("version", 2);
            }
            if (withUnknownFields.path("error") instanceof ObjectNode error) {
                error.putObject("futureErrorField").put("version", 2);
            }

            ApplicationProtocol.ProtocolMessage decoded = ApplicationProtocol.decode(withUnknownFields);
            assertInstanceOf(fixture.messageType(), decoded, fixture.fileName());
            if (decoded instanceof ApplicationProtocol.Request request) {
                assertEquals(fixture.method(), request.method(), fixture.fileName());
                assertInstanceOf(fixture.envelopeType(), request.params(), fixture.fileName());
                assertCommonFields(request.params(), fixture.fileName());
            } else if (decoded instanceof ApplicationProtocol.Notification notification) {
                assertEquals(fixture.method(), notification.method(), fixture.fileName());
                assertInstanceOf(fixture.envelopeType(), notification.params(), fixture.fileName());
                assertCommonFields(notification.params(), fixture.fileName());
            }

            JsonNode encoded = ApplicationProtocol.encode(decoded);
            JsonNode serialized = ApplicationProtocol.serializeTree(decoded);
            assertMessageMatches(canonical, serialized, fixture.fileName());
            assertMessageMatches(canonical, encoded, fixture.fileName() + " encode");
        }
    }

    private static void assertMessageMatches(JsonNode canonical, JsonNode actual, String fixtureName) {
        assertEquals(canonical.path("jsonrpc"), actual.path("jsonrpc"), fixtureName);
        assertEquals(canonical.path("id"), actual.path("id"), fixtureName);
        assertEquals(canonical.path("method"), actual.path("method"), fixtureName);
            if (canonical.has("params")) {
            assertEnvelopeMatches(canonical.path("params"), actual.path("params"), fixtureName);
            } else if (canonical.has("result")) {
            ObjectNode normalizedResult = actual.path("result").deepCopy();
                normalizedResult.remove("futureResultField");
            assertEquals(canonical.path("result"), normalizedResult, fixtureName);
            } else {
            ObjectNode normalizedError = actual.path("error").deepCopy();
                normalizedError.remove("futureErrorField");
            assertEquals(canonical.path("error"), normalizedError, fixtureName);
            }
    }

    @Test
    void fixturesCoverAllNineteenApplicationMethodsAndBothQueryResponses() throws IOException {
        Set<String> fixtureMethods = FIXTURES.stream()
                .map(FixtureCase::method)
                .filter(method -> method != null)
                .collect(Collectors.toSet());

        assertEquals(ApplicationProtocol.ApplicationMethod.wireNames(), fixtureMethods);
        assertEquals(19, fixtureMethods.size());

        Path contractDirectory = repositoryRoot()
                .resolve("docs/superpowers/contracts/huitai-business-desktop-agent");
        JsonNode statusResult = ApplicationProtocol.readTree(
                Files.readString(contractDirectory.resolve("action-status-result.json")));
        JsonNode actionResult = ApplicationProtocol.readTree(
                Files.readString(contractDirectory.resolve("action-result-get-result.json")));
        JsonNode protocolError = ApplicationProtocol.readTree(
                Files.readString(contractDirectory.resolve("protocol-error.json")));

        assertEquals(Map.of("executionId", "execution-1", "state", "executing"),
                ApplicationProtocol.convertValue(statusResult.path("result"), Map.class));
        assertEquals("succeeded", actionResult.path("result").path("state").textValue());
        assertTrue(actionResult.path("result").path("output").path("accepted").booleanValue());
        assertEquals(-32041, protocolError.path("error").path("code").intValue());
        assertEquals("PROTOCOL_ERROR", protocolError.path("error").path("message").textValue());
        assertEquals("identity_scope_mismatch", protocolError.path("error").path("data").path("reason").textValue());
    }

    @Test
    void protocolRecordsDefensivelyCopyIdentityCollectionsAndJsonTrees() {
        Set<String> roles = new LinkedHashSet<>(Set.of("lawyer"));
        Set<String> permissions = new LinkedHashSet<>(Set.of("framework:read"));
        ApplicationIdentityMessage identity = new ApplicationIdentityMessage(
                "1.0", "desktop-1", "desktop-session-1", "auth-session-1", 8, 1,
                "2026-07-16T10:00:00Z", "user-1", "tenant-1", "platform-1",
                true, roles, permissions);
        roles.add("future-role");
        permissions.add("future:write");

        ObjectNode payload = ApplicationProtocol.objectNode().put("state", "executing");
        ApplicationActionMessage action = new ApplicationActionMessage(
                "1.0", "desktop-1", "desktop-session-1", "auth-session-1", 8, 2,
                "2026-07-16T10:00:00Z", "user-1", "tenant-1", "platform-1",
                "thread-1", "turn-1", "tool-call-1", "execution-1", payload);
        payload.put("state", "mutated");

        assertEquals(Set.of("lawyer"), identity.roles());
        assertEquals(Set.of("framework:read"), identity.permissions());
        assertThrows(UnsupportedOperationException.class, () -> identity.roles().add("forbidden"));
        assertEquals("executing", action.payload().path("state").textValue());

        ((ObjectNode) action.payload()).put("state", "accessor-mutated");
        assertEquals("executing", action.payload().path("state").textValue());
    }

    private static void assertCommonFields(ApplicationEnvelope envelope, String fixtureName) {
        assertEquals(ApplicationProtocol.PROTOCOL_VERSION, envelope.protocolVersion(), fixtureName);
        assertEquals("desktop-1", envelope.desktopInstanceId(), fixtureName);
        assertEquals("desktop-session-1", envelope.desktopSessionId(), fixtureName);
        assertEquals("auth-session-1", envelope.authSessionId(), fixtureName);
        assertEquals(8, envelope.identityEpoch(), fixtureName);
        assertTrue(envelope.sequence() > 0, fixtureName);
        assertEquals("2026-07-16T10:00:00Z", envelope.generatedAt(), fixtureName);
        assertEquals("user-1", envelope.userId(), fixtureName);
        assertEquals("tenant-1", envelope.tenantId(), fixtureName);
        assertEquals("platform-1", envelope.platformId(), fixtureName);
    }

    private static void assertEnvelopeMatches(JsonNode canonical, JsonNode encoded, String fixtureName) {
        ObjectNode normalized = encoded.deepCopy();
        normalized.remove("futureEnvelopeField");
        if (normalized.path("payload") instanceof ObjectNode payload) {
            payload.remove("futurePayloadField");
        }
        assertEquals(canonical, normalized, fixtureName);
    }

    private static FixtureCase request(String fileName, String method, Class<? extends ApplicationEnvelope> envelopeType) {
        return new FixtureCase(fileName, method, ApplicationProtocol.Request.class, envelopeType);
    }

    private static FixtureCase notification(String fileName, String method,
                                            Class<? extends ApplicationEnvelope> envelopeType) {
        return new FixtureCase(fileName, method, ApplicationProtocol.Notification.class, envelopeType);
    }

    private static FixtureCase response(String fileName,
                                        Class<? extends ApplicationProtocol.ProtocolMessage> messageType) {
        return new FixtureCase(fileName, null, messageType, null);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("business-desktop/settings.gradle.kts"))
                    && Files.isDirectory(current.resolve("docs/superpowers"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate BaBiQ repository root");
    }

    private record FixtureCase(
            String fileName,
            String method,
            Class<? extends ApplicationProtocol.ProtocolMessage> messageType,
            Class<? extends ApplicationEnvelope> envelopeType
    ) {
    }
}
