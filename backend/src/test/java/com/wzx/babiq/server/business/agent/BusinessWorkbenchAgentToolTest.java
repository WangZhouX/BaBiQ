package com.wzx.babiq.server.business.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import com.wzx.babiq.server.business.workbench.BusinessWorkbenchService;
import com.wzx.babiq.server.observability.TurnObservationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BusinessWorkbenchAgentToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
    private final BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
    private final BusinessWorkbenchService workbench = mock(BusinessWorkbenchService.class);
    private final BusinessScheduleService schedules = mock(BusinessScheduleService.class);
    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-1", "desktop-1", "session-1", "ws-1");
    private final TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
            "reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 7,
            "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("workbench.read"));
    private final BusinessIdentityScope scope = BusinessIdentityScope.scoped(
            "desktop-1", "session-1", "auth-1", 7, "user-1", "tenant-1", "2");
    private final ReadyOaSessionLease lease = new ReadyOaSessionLease(
            "auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1",
            "2", 7, "keystore://credential", 1, Instant.now());
    private BusinessWorkbenchReadTool tool;

    @BeforeEach
    void setUp() {
        when(scopes.resolveActive(scope)).thenReturn(Optional.of(
                new BusinessIdentityScopeService.ActiveBusinessIdentity(connection, identity)));
        when(sessions.currentReady(connection, identity)).thenReturn(Optional.of(lease));
        tool = new BusinessWorkbenchReadTool(
                workbench, schedules, new BusinessAgentToolSupport(scopes, sessions, json));
    }

    @Test
    void snapshotUsesTheFrozenIdentityAndCurrentReadyLease() throws Exception {
        BusinessWorkbenchDtos.Snapshot snapshot = new BusinessWorkbenchDtos.Snapshot(
                BusinessWorkbenchDtos.Section.empty(),
                BusinessWorkbenchDtos.Section.empty(),
                BusinessWorkbenchDtos.Section.empty(),
                BusinessWorkbenchDtos.Section.empty(),
                BusinessWorkbenchDtos.Section.empty(),
                BusinessWorkbenchDtos.Section.empty(),
                List.of());
        when(workbench.snapshot(lease, identity, "2026-08", "2026-08-01")).thenReturn(snapshot);

        String result = tool.read(
                new BusinessWorkbenchReadTool.ReadRequest(
                        "snapshot", "2026-08", "2026-08-01", null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null),
                toolContext(scope));

        JsonNode payload = json.readTree(result);
        assertThat(payload.path("ok").asBoolean()).isTrue();
        assertThat(payload.path("data").path("identityEpoch").asLong()).isEqualTo(7);
        assertThat(payload.toString()).doesNotContain(
                "keystore://credential", "auth-1", "tenant-1", "desktop-1");
        verify(workbench).snapshot(lease, identity, "2026-08", "2026-08-01");
    }

    @Test
    void scheduleDayDelegatesOnlyThroughTheTypedScheduleService() throws Exception {
        BusinessWorkbenchDtos.ScheduleDayEnvelope response =
                new BusinessWorkbenchDtos.ScheduleDayEnvelope(7, 7, List.of(
                        new BusinessWorkbenchDtos.ScheduleDayGroup("09:00", false, List.of(
                                new BusinessWorkbenchDtos.ScheduleDayItem(
                                        "schedule-1", "庭审", "2026-08-01 09:00:00", false)))));
        when(schedules.day(eqLease(), eqIdentity(), any())).thenReturn(response);

        String result = tool.read(
                new BusinessWorkbenchReadTool.ReadRequest(
                        "schedule_day", null, null, "2026-08-01", "PERSONAL", null,
                        true, null, null, null, null, null, null, null, null, null, null),
                toolContext(scope));

        assertThat(json.readTree(result).path("data").path("groups").get(0)
                .path("items").get(0).path("title").asText()).isEqualTo("庭审");
        verify(schedules).day(eqLease(), eqIdentity(), any(BusinessWorkbenchDtos.ScheduleQuery.class));
        verifyNoInteractions(workbench);
    }

    @Test
    void missingOrDriftedIdentityFailsBeforeAnyBusinessCall() throws Exception {
        String missing = tool.read(
                new BusinessWorkbenchReadTool.ReadRequest(
                        "navigation", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                new ToolContext(Map.of()));
        assertThat(json.readTree(missing).path("code").asText())
                .isEqualTo("BUSINESS_AGENT_CONTEXT_MISSING");

        BusinessIdentityScope drifted = BusinessIdentityScope.scoped(
                "desktop-1", "session-1", "auth-1", 8, "user-1", "tenant-1", "2");
        String stale = tool.read(
                new BusinessWorkbenchReadTool.ReadRequest(
                        "navigation", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                toolContext(drifted));
        assertThat(json.readTree(stale).path("code").asText())
                .isEqualTo("BUSINESS_SESSION_STALE");
        verifyNoInteractions(workbench, schedules);
        verify(sessions, never()).captureReady(any(TrustedDesktopConnection.class));
    }

    private ToolContext toolContext(BusinessIdentityScope value) {
        TurnObservationContext observation = TurnObservationContext.start(
                "thread-1", "turn-1", "provider-1", "model-1", value);
        return new ToolContext(Map.of(
                BusinessIdentityScope.METADATA_KEY, value,
                TurnObservationContext.METADATA_KEY, observation));
    }

    private ReadyOaSessionLease eqLease() {
        return org.mockito.ArgumentMatchers.eq(lease);
    }

    private TrustedBusinessIdentity eqIdentity() {
        return org.mockito.ArgumentMatchers.eq(identity);
    }
}
