package com.wzx.babiq.server.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.protocol.ApplicationActionMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocol;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDesktopContractSeamIT {

    private static final List<String> FIXTURES = List.of(
            "catalog-register.json", "catalog-update.json", "context-publish.json",
            "identity-bind.json", "identity-update.json", "action-request.json",
            "action-cancel.json", "action-accepted.json", "action-previewed.json",
            "action-approval-required.json", "action-running.json", "action-completed.json",
            "action-failed.json", "action-rejected.json", "action-canceled.json",
            "action-expired.json", "action-outcome-unknown.json", "action-status.json",
            "action-result-get.json", "action-status-result.json",
            "action-result-get-result.json", "protocol-error.json");

    private static final Map<String, PendingApplicationAction.State> LIFECYCLE_METHODS = Map.ofEntries(
            Map.entry("application/action/accepted", PendingApplicationAction.State.ACCEPTED),
            Map.entry("application/action/previewed", PendingApplicationAction.State.PREVIEWED),
            Map.entry("application/action/approval-required", PendingApplicationAction.State.APPROVAL_REQUIRED),
            Map.entry("application/action/running", PendingApplicationAction.State.RUNNING),
            Map.entry("application/action/completed", PendingApplicationAction.State.COMPLETED),
            Map.entry("application/action/failed", PendingApplicationAction.State.FAILED),
            Map.entry("application/action/rejected", PendingApplicationAction.State.REJECTED),
            Map.entry("application/action/canceled", PendingApplicationAction.State.CANCELED),
            Map.entry("application/action/expired", PendingApplicationAction.State.EXPIRED),
            Map.entry("application/action/outcome-unknown", PendingApplicationAction.State.OUTCOME_UNKNOWN));

    @Test
    void kotlinCanonicalFixturesAndFrameworkLifecycleRowsMeetTheJavaBridgeVocabulary() throws IOException {
        Path contracts = repositoryRoot().resolve("docs/superpowers/contracts/huitai-business-desktop-agent");
        Set<String> actual;
        try (var files = Files.list(contracts)) {
            actual = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(FIXTURES);

        Map<String, PendingApplicationAction.State> decodedLifecycle = new LinkedHashMap<>();
        for (String fixture : FIXTURES) {
            JsonNode canonical = ApplicationProtocol.readTree(Files.readString(contracts.resolve(fixture)));
            ApplicationProtocol.ProtocolMessage decoded = ApplicationProtocol.decode(canonical);
            assertThat(ApplicationProtocol.encode(decoded)).as(fixture).isEqualTo(canonical);
            if (decoded instanceof ApplicationProtocol.Notification notification
                    && notification.params() instanceof ApplicationActionMessage action
                    && LIFECYCLE_METHODS.containsKey(notification.method())) {
                PendingApplicationAction.State state = LIFECYCLE_METHODS.get(notification.method());
                decodedLifecycle.put(notification.method(), state);
                assertCommonCorrelation(action, fixture);
                assertThat(action.payload().path("state").asText()).as(fixture)
                        .isEqualTo(wireState(state));
            }
        }

        assertThat(decodedLifecycle).containsExactlyInAnyOrderEntriesOf(LIFECYCLE_METHODS);
        assertThat(EnumSet.copyOf(decodedLifecycle.values())).containsExactlyInAnyOrder(
                PendingApplicationAction.State.ACCEPTED,
                PendingApplicationAction.State.PREVIEWED,
                PendingApplicationAction.State.APPROVAL_REQUIRED,
                PendingApplicationAction.State.RUNNING,
                PendingApplicationAction.State.COMPLETED,
                PendingApplicationAction.State.FAILED,
                PendingApplicationAction.State.REJECTED,
                PendingApplicationAction.State.CANCELED,
                PendingApplicationAction.State.EXPIRED,
                PendingApplicationAction.State.OUTCOME_UNKNOWN);

        assertLifecycle("read", List.of("accepted", "running", "completed"));
        assertLifecycle("reversible patch", List.of("accepted", "previewed", "running", "completed"));
        assertLifecycle("high risk approved",
                List.of("accepted", "previewed", "approval_required", "running", "completed"));
        assertLifecycle("high risk denied",
                List.of("accepted", "previewed", "approval_required", "canceled"));
        assertLifecycle("cancel before execute", List.of("accepted", "previewed", "canceled"));
        assertReconciledLifecycle("response lost",
                List.of("accepted", "running", "outcome_unknown"), "completed");
    }

    private static void assertLifecycle(String label, List<String> states) {
        PendingApplicationAction.State previous = PendingApplicationAction.State.REQUESTED;
        for (String wire : states) {
            PendingApplicationAction.State next = fromWire(wire);
            assertThat(isAllowed(previous, next)).as(label + ": " + previous + " -> " + next).isTrue();
            previous = next;
        }
        assertThat(previous.isTerminal()).as(label).isTrue();
    }

    private static void assertReconciledLifecycle(String label, List<String> firstTerminalPath, String lateTerminal) {
        assertLifecycle(label, firstTerminalPath);
        assertThat(fromWire(firstTerminalPath.getLast())).isEqualTo(PendingApplicationAction.State.OUTCOME_UNKNOWN);
        assertThat(fromWire(lateTerminal).isTerminal()).as(label + " late reconciliation").isTrue();
    }

    private static boolean isAllowed(PendingApplicationAction.State from, PendingApplicationAction.State to) {
        return switch (to) {
            case ACCEPTED -> from == PendingApplicationAction.State.REQUESTED;
            case PREVIEWED -> from == PendingApplicationAction.State.ACCEPTED;
            case APPROVAL_REQUIRED -> from == PendingApplicationAction.State.PREVIEWED;
            case RUNNING -> from == PendingApplicationAction.State.ACCEPTED
                    || from == PendingApplicationAction.State.PREVIEWED
                    || from == PendingApplicationAction.State.APPROVAL_REQUIRED;
            case COMPLETED, OUTCOME_UNKNOWN -> from == PendingApplicationAction.State.RUNNING;
            case FAILED, REJECTED, CANCELED, EXPIRED -> !from.isTerminal();
            case REQUESTED -> false;
        };
    }

    private static PendingApplicationAction.State fromWire(String value) {
        return switch (value) {
            case "accepted" -> PendingApplicationAction.State.ACCEPTED;
            case "previewed" -> PendingApplicationAction.State.PREVIEWED;
            case "approval_required" -> PendingApplicationAction.State.APPROVAL_REQUIRED;
            case "running" -> PendingApplicationAction.State.RUNNING;
            case "completed" -> PendingApplicationAction.State.COMPLETED;
            case "failed" -> PendingApplicationAction.State.FAILED;
            case "rejected" -> PendingApplicationAction.State.REJECTED;
            case "canceled" -> PendingApplicationAction.State.CANCELED;
            case "expired" -> PendingApplicationAction.State.EXPIRED;
            case "outcome_unknown" -> PendingApplicationAction.State.OUTCOME_UNKNOWN;
            default -> throw new IllegalArgumentException("unknown lifecycle state");
        };
    }

    private static String wireState(PendingApplicationAction.State state) {
        return switch (state) {
            case APPROVAL_REQUIRED -> "waiting_approval";
            case RUNNING -> "executing";
            case COMPLETED -> "succeeded";
            default -> state.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static void assertCommonCorrelation(ApplicationActionMessage action, String fixture) {
        assertThat(action.protocolVersion()).as(fixture).isEqualTo(ApplicationProtocol.PROTOCOL_VERSION);
        assertThat(action.desktopInstanceId()).as(fixture).isEqualTo("desktop-1");
        assertThat(action.desktopSessionId()).as(fixture).isEqualTo("desktop-session-1");
        assertThat(action.authSessionId()).as(fixture).isEqualTo("auth-session-1");
        assertThat(action.identityEpoch()).as(fixture).isEqualTo(8);
        assertThat(action.threadId()).as(fixture).isEqualTo("thread-1");
        assertThat(action.turnId()).as(fixture).isEqualTo("turn-1");
        assertThat(action.toolCallId()).as(fixture).isEqualTo("tool-call-1");
        assertThat(action.executionId()).as(fixture).isEqualTo("execution-1");
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
}
