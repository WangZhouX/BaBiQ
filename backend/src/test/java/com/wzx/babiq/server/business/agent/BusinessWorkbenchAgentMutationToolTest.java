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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BusinessWorkbenchAgentMutationToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
    private final BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
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
    private BusinessScheduleMutationTool tool;

    @BeforeEach
    void setUp() {
        when(scopes.resolveActive(scope)).thenReturn(Optional.of(
                new BusinessIdentityScopeService.ActiveBusinessIdentity(connection, identity)));
        when(sessions.currentReady(connection, identity)).thenReturn(Optional.of(lease));
        tool = new BusinessScheduleMutationTool(
                schedules, new BusinessAgentToolSupport(scopes, sessions, json));
    }

    @Test
    void completionUsesTheCurrentLeaseAndReturnsOnlyTheStableEnvelope() throws Exception {
        when(schedules.setCompletion(lease, identity, "schedule-1", true))
                .thenReturn(new BusinessWorkbenchDtos.ScheduleCompletionResult(7, 7, true, true, 3));

        String result = tool.mutate(
                new BusinessScheduleMutationTool.MutationRequest(
                        "set_completion", "schedule-1", true, null, null, null, null),
                toolContext(scope));

        JsonNode payload = json.readTree(result);
        assertThat(payload.path("ok").asBoolean()).isTrue();
        assertThat(payload.path("data").path("completed").asBoolean()).isTrue();
        assertThat(payload.toString()).doesNotContain("keystore://credential", "tenant-1", "auth-1");
        verify(schedules).setCompletion(lease, identity, "schedule-1", true);
    }

    @Test
    void createMapsOnlyTheTypedAgentFieldsIntoTheExistingValidatedServiceContract() {
        BusinessScheduleMutationTool.ScheduleCreateInput create =
                new BusinessScheduleMutationTool.ScheduleCreateInput(
                        "agent-op-1", "PERSONAL", null, null, "会见当事人", "type-1",
                        "2026-08-01 10:00:00", false, 2, "准备材料", List.of(30),
                        List.of(), 0, 0);
        when(schedules.create(any(), any(), any())).thenReturn(
                new BusinessWorkbenchDtos.MutationEnvelope(7, 7, 1, true));

        String result = tool.mutate(
                new BusinessScheduleMutationTool.MutationRequest(
                        "create", null, null, null, null, null, create),
                toolContext(scope));

        assertThat(result).contains("\"ok\":true");
        verify(schedules).create(lease, identity, new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "agent-op-1", "PERSONAL", null, null, "会见当事人", "type-1",
                "2026-08-01 10:00:00", false, 2, "准备材料", List.of(30),
                List.of(), null, null, null, 0, 0));
    }

    @Test
    void invalidOrStaleRequestsFailBeforeAnyOaWrite() throws Exception {
        String invalid = tool.mutate(
                new BusinessScheduleMutationTool.MutationRequest(
                        "raw_oa_method", null, null, null, null, null, null),
                toolContext(scope));
        assertThat(json.readTree(invalid).path("code").asText())
                .isEqualTo("BUSINESS_INVALID_INPUT");

        when(sessions.currentReady(connection, identity)).thenReturn(Optional.empty());
        String stale = tool.mutate(
                new BusinessScheduleMutationTool.MutationRequest(
                        "set_completion", "schedule-1", true, null, null, null, null),
                toolContext(scope));
        assertThat(json.readTree(stale).path("code").asText())
                .isEqualTo("BUSINESS_SESSION_STALE");
        verifyNoInteractions(schedules);
    }

    private ToolContext toolContext(BusinessIdentityScope value) {
        TurnObservationContext observation = TurnObservationContext.start(
                "thread-1", "turn-1", "provider-1", "model-1", value);
        return new ToolContext(Map.of(
                BusinessIdentityScope.METADATA_KEY, value,
                TurnObservationContext.METADATA_KEY, observation));
    }
}
