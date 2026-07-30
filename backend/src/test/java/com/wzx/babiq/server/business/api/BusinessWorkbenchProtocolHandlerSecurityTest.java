package com.wzx.babiq.server.business.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchException;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.workbench.BusinessWorkbenchService;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BusinessWorkbenchProtocolHandlerSecurityTest {
    @Test
    void page_rejects_tenant_and_access_token_params_before_service_call() throws Exception {
        BusinessWorkbenchService service = mock(BusinessWorkbenchService.class);
        Harness harness = harness(service);

        assertThatThrownBy(() -> harness.handler.handle("business/workbench/page/get",
                harness.mapper.readTree("{\"kind\":\"CASE\",\"scope\":\"ALL\",\"tenantId\":\"evil\"}"),
                harness.session)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(service);
    }

    @Test
    void every_workbench_method_resolves_finalized_connection_and_ready_lease() throws Exception {
        BusinessWorkbenchService service = mock(BusinessWorkbenchService.class);
        Harness harness = harness(service);
        when(service.snapshot(any(), any(), any(), any())).thenReturn(
                new BusinessWorkbenchDtos.Snapshot(
                        BusinessWorkbenchDtos.Section.ok(List.of()),
                        BusinessWorkbenchDtos.Section.ok(List.of()),
                        BusinessWorkbenchDtos.Section.ok(List.of()),
                        BusinessWorkbenchDtos.Section.ok(Map.of()),
                        BusinessWorkbenchDtos.Section.ok(List.of()),
                        BusinessWorkbenchDtos.Section.ok(Map.of()),
                        List.of()));
        when(service.homeInfo(any(), any())).thenReturn(new BusinessWorkbenchDtos.HomeInfoEnvelope(
                7, 3, BusinessWorkbenchDtos.Section.ok(Map.of("name", "user"))));
        when(service.navigation(any(), any())).thenReturn(new BusinessWorkbenchDtos.NavigationEnvelope(
                7, 3, List.of(new BusinessWorkbenchDtos.NavigationTarget("WORKBENCH", "/", "工作台"))));
        when(service.teamRoles(any(), any(), any(), any())).thenReturn(new BusinessWorkbenchDtos.TeamRolesEnvelope(
                7, 3, List.of(new BusinessWorkbenchDtos.TeamRole("owner", "Owner"))));
        when(service.page(any(), any(), any())).thenReturn(new BusinessWorkbenchDtos.PageResult(0, 1, 20, List.of()));

        harness.handler.handle("business/workbench/get", harness.mapper.createObjectNode(), harness.session);
        harness.handler.handle("business/workbench/navigation/get", harness.mapper.createObjectNode(), harness.session);
        harness.handler.handle("business/workbench/home-info/get", harness.mapper.createObjectNode(), harness.session);
        harness.handler.handle("business/workbench/team-roles/list",
                harness.mapper.readTree("{\"kind\":\"CASE\",\"teamId\":\"team-1\"}"), harness.session);
        harness.handler.handle("business/workbench/page/get",
                harness.mapper.readTree("{\"kind\":\"CASE\",\"scope\":\"ALL\",\"pageNo\":1,\"pageSize\":20,\"filters\":{}}"),
                harness.session);

        verify(harness.resolver, times(5)).requireFinalized(harness.session);
        verify(harness.sessions, times(5)).captureReady(harness.connection);
        verify(harness.identities, times(5)).current(harness.connection);
    }

    @Test
    void sort_update_requires_an_explicit_expected_revision() throws Exception {
        BusinessWorkbenchService service = mock(BusinessWorkbenchService.class);
        Harness harness = harness(service);

        assertThatThrownBy(() -> harness.handler.handle("business/workbench/sort/update",
                harness.mapper.readTree("{\"kind\":\"SHORTCUT\",\"ids\":[\"shortcut-1\"]}"),
                harness.session)).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(harness.schedules);
    }

    @Test
    void rejected_remote_sort_maps_to_stable_sanitized_remote_protocol_error() throws Exception {
        BusinessWorkbenchService service = mock(BusinessWorkbenchService.class);
        Harness harness = harness(service);
        when(harness.schedules.updateSort(any(), any(), eq("SHORTCUT"),
                eq(List.of("shortcut-1")), eq(0L)))
                .thenThrow(new OaWorkbenchException(
                        "REMOTE_PROTOCOL_ERROR oa-secret-response"));

        JsonRpcException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> harness.handler.handle("business/workbench/sort/update",
                        harness.mapper.readTree("""
                                {"kind":"SHORTCUT","ids":["shortcut-1"],"expectedRevision":0}
                                """),
                        harness.session),
                JsonRpcException.class);

        assertThat(failure.errorCode()).isEqualTo(JsonRpcErrorCode.BUSINESS_REMOTE_PROTOCOL_ERROR);
        assertThat(failure.getMessage()).isEqualTo("Remote service protocol error");
        assertThat(failure.getMessage()).doesNotContain("oa-secret-response", "BUSINESS_REMOTE_REJECTED");
        assertThat(failure.errorData()).isEqualTo(Map.of(
                "businessCode", "BUSINESS_REMOTE_PROTOCOL_ERROR",
                "retryable", false));
    }

    private static Harness harness(BusinessWorkbenchService service) {
        ObjectMapper mapper = new ObjectMapper();
        BusinessDesktopConnectionResolver resolver = mock(BusinessDesktopConnectionResolver.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessScheduleService schedules = mock(BusinessScheduleService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection("reservation-1", "desktop-1", "session-1", "ws-1");
        ReadyOaSessionLease lease = new ReadyOaSessionLease("auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1", "2", 3, "credential-1", 1, Instant.now());
        when(resolver.requireFinalized(session)).thenReturn(connection);
        when(sessions.captureReady(connection)).thenReturn(lease);
        TrustedBusinessIdentity identity = new TrustedBusinessIdentity("reservation-1", "ws-1", "desktop-1", "session-1",
                "auth-1", 7, "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("*"));
        when(identities.current(connection)).thenReturn(Optional.of(identity));
        return new Harness(new BusinessWorkbenchProtocolHandler(
                service, mapper, resolver, sessions, identities, schedules), mapper,
                resolver, sessions, identities, schedules, session, connection);
    }

    private record Harness(BusinessWorkbenchProtocolHandler handler, ObjectMapper mapper,
                           BusinessDesktopConnectionResolver resolver, BusinessOaSessionRegistry sessions,
                           ApplicationIdentityRegistry identities, BusinessScheduleService schedules,
                           WebSocketSession session,
                           TrustedDesktopConnection connection) {}
}
