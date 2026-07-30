package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessResourceHandleRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BusinessWorkbenchServiceSecurityTest {
    @Test
    void page_uses_ready_lease_executor_and_never_accepts_client_credentials() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessDataScopeValidator scopes = new BusinessDataScopeValidator();
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(executor.execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    OaAuthenticatedRequestExecutor.CredentialOperation<Object> operation = invocation.getArgument(2);
                    return operation.execute("server-token".toCharArray());
                });
        when(gateway.page(any(), eq("tenant-1"), any())).thenReturn(
                new OaWorkbenchDtos.PageResult(1, 1, 20, List.of(Map.of("id", "case-1"))));

        BusinessWorkbenchService service = new BusinessWorkbenchService(gateway, scopes, sessions, executor);
        BusinessWorkbenchDtos.PageResult result = service.page(lease, identity(),
                new BusinessWorkbenchDtos.PageRequest("CASE", "ALL", null, null, 1, 20, Map.of()));

        assertThat(result.items()).singleElement().extracting(BusinessWorkbenchDtos.PageRow::id)
                .isEqualTo("case-1");
        verify(executor).execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any());
        verify(gateway).page(any(), eq("tenant-1"), any());
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void page_preserves_the_four_kind_contract_while_canonicalizing_filter_values() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.page(any(), eq("tenant-1"), any())).thenReturn(
                new OaWorkbenchDtos.PageResult(0, 7, 13, List.of()));
        when(gateway.teams(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "team-9", "name", "Team Nine")));
        when(gateway.teamRoles(eq("tenant-1"), any(), eq("team-9"), any())).thenReturn(
                List.of(Map.of("roleCode", "role-x", "roleName", "Role X")));
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);

        service.page(lease, identity(), pageRequest("CASE", "status", "02"));
        service.page(lease, identity(), pageRequest("APPOINTMENT", "consultMode", "02"));
        service.page(lease, identity(), pageRequest("COUNSELOR_SERVICE", "serviceStatus", "01"));
        service.page(lease, identity(), pageRequest("VISIT", "visitObj", "02"));

        org.mockito.ArgumentCaptor<OaWorkbenchDtos.PageQuery> query =
                org.mockito.ArgumentCaptor.forClass(OaWorkbenchDtos.PageQuery.class);
        verify(gateway, times(4)).page(query.capture(), eq("tenant-1"), any());
        assertThat(query.getAllValues()).containsExactly(
                new OaWorkbenchDtos.PageQuery("CASE", 1007, "TEAM", "team-9", "role-x", 7, 13, "2"),
                new OaWorkbenchDtos.PageQuery("APPOINTMENT", 1006, "TEAM", "team-9", "role-x", 7, 13, "2"),
                new OaWorkbenchDtos.PageQuery("COUNSELOR_SERVICE", 1003, "TEAM", "team-9", "role-x", 7, 13, "1"),
                new OaWorkbenchDtos.PageQuery("VISIT", 1004, "TEAM", "team-9", "role-x", 7, 13, "2"));
        verify(executor, times(4)).execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any());

        InOrder order = inOrder(gateway);
        for (String kind : List.of("CASE", "APPOINTMENT", "COUNSELOR_SERVICE", "VISIT")) {
            order.verify(gateway).teams(eq("tenant-1"), any());
            order.verify(gateway).teamRoles(eq("tenant-1"), any(), eq("team-9"), eq(kind));
            order.verify(gateway).page(any(), eq("tenant-1"), any());
        }
    }

    @Test
    void page_rejects_team_outside_the_current_ready_lease_projection_before_page_call() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.teams(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "team-other", "name", "Other Team")));
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);

        assertThatThrownBy(() -> service.page(lease, identity(), pageRequest("CASE", "status", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("team is not authorized");

        verify(executor).execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any());
        verify(gateway).teams(eq("tenant-1"), any());
        verify(gateway, never()).teamRoles(any(), any(), any(), any());
        verify(gateway, never()).page(any(), any(), any());
    }

    @Test
    void page_rejects_role_outside_the_current_team_module_projection_before_page_call() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.teams(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "team-9", "name", "Team Nine")));
        when(gateway.teamRoles(eq("tenant-1"), any(), eq("team-9"), eq("CASE"))).thenReturn(
                List.of(Map.of("roleCode", "role-other", "roleName", "Other Role")));
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);

        assertThatThrownBy(() -> service.page(lease, identity(), pageRequest("CASE", "status", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("role is not authorized");

        verify(executor).execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any());
        InOrder order = inOrder(gateway);
        order.verify(gateway).teams(eq("tenant-1"), any());
        order.verify(gateway).teamRoles(eq("tenant-1"), any(), eq("team-9"), eq("CASE"));
        verify(gateway, never()).page(any(), any(), any());
    }

    @Test
    void team_roles_rejects_team_outside_the_current_ready_lease_projection() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.teams(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "team-other", "name", "Other Team")));
        when(gateway.teamRoles(eq("tenant-1"), any(), eq("team-9"), eq("CASE"))).thenReturn(
                List.of(Map.of("roleCode", "role-x", "roleName", "Role X")));
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);

        assertThatThrownBy(() -> service.teamRoles(lease, identity(), "CASE", "team-9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("team is not authorized");

        verify(executor).execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any());
        verify(gateway).teams(eq("tenant-1"), any());
        verify(gateway, never()).teamRoles(any(), any(), any(), any());
    }

    @Test
    void team_scope_without_role_revalidates_membership_per_lease_without_role_lookup() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease firstLease = lease();
        ReadyOaSessionLease secondLease = new ReadyOaSessionLease(
                "auth-2", "desktop-2", "session-2", "ws-2", "user-2", "tenant-2",
                "2", 4, "credential-2", 1, Instant.now());
        TrustedBusinessIdentity secondIdentity = new TrustedBusinessIdentity(
                "reservation-2", "ws-2", "desktop-2", "session-2", "auth-2", 8,
                "user-2", "tenant-2", "2", Set.of("lawyer"), Set.of("*"));
        when(sessions.isCurrent(firstLease)).thenReturn(true);
        when(sessions.isCurrent(secondLease)).thenReturn(true);
        when(gateway.teams(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "team-9", "name", "Team Nine")));
        when(gateway.teams(eq("tenant-2"), any())).thenReturn(
                List.of(Map.of("id", "team-other", "name", "Other Team")));
        when(gateway.page(any(), eq("tenant-1"), any())).thenReturn(
                new OaWorkbenchDtos.PageResult(0, 1, 20, List.of()));
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);
        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "TEAM", "team-9", null, 1, 20, Map.of());

        service.page(firstLease, identity(), request);
        assertThatThrownBy(() -> service.page(secondLease, secondIdentity, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("team is not authorized");

        verify(gateway).teams(eq("tenant-1"), any());
        verify(gateway).teams(eq("tenant-2"), any());
        verify(gateway, never()).teamRoles(any(), any(), any(), any());
        verify(gateway).page(any(), eq("tenant-1"), any());
        verify(gateway, never()).page(any(), eq("tenant-2"), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScalarFilterValues")
    void page_rejects_invalid_scalar_filter_values_before_remote_execution(String description,
                                                                             Object invalidValue) {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);

        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "ALL", null, null, 1, 20, Map.of("status", invalidValue));

        assertThatThrownBy(() -> service.page(lease, identity(), request))
                .as(description)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid filter");
        verifyNoInteractions(executor, gateway);
    }

    @Test
    void page_rejects_structured_and_oversized_filter_values_before_remote_execution() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);
        List<Object> invalidValues = List.of(
                new StringBuilder("1"),
                List.of(1),
                Map.of("nested", 1),
                new int[]{1},
                Boolean.TRUE,
                12.5d,
                new Object(),
                "1".repeat(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH + 1));

        for (Object invalidValue : invalidValues) {
            BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                    "CASE", "ALL", null, null, 1, 20, Map.of("status", invalidValue));
            assertThatThrownBy(() -> service.page(lease, identity(), request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(executor, gateway);
    }

    @Test
    void snapshot_sanitizes_each_remote_section_before_it_reaches_desktop_dto() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.notices(eq("tenant-1"), any(), eq(1), eq(10))).thenReturn(
                new OaWorkbenchDtos.NoticePage(1, 1, 10,
                        List.of(Map.of("id", "notice-1", "title", "公告", "tenantId", "drop"))));
        when(gateway.shortcuts(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "shortcut-1", "configName", "新建案件", "url", "https://oa.example/drop")));
        when(gateway.summary(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("configCode", "case_handle", "total", 2, "stat", Map.of("handling", 1),
                        "relatedIds", List.of("drop"))));
        when(gateway.homeInfo(eq("tenant-1"), any())).thenReturn(
                new OaWorkbenchDtos.UserHomeInfo("user-1", "tenant-1", "律师", "https://oa.example/avatar", Map.of(
                        "nickname", "律师", "password", "drop")));
        when(gateway.teams(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "team-1", "name", "团队", "accessToken", "drop")));
        when(gateway.scheduleCount(eq("tenant-1"), any())).thenReturn(
                Map.of("count", 1, "remoteUrl", "https://oa.example/schedule"));
        when(gateway.scheduleDay(eq("tenant-1"), any())).thenReturn(
                Map.of("list", List.of(Map.of("id", "schedule-1", "title", "会见", "tenantId", "drop"))));

        BusinessWorkbenchDtos.Snapshot snapshot = new BusinessWorkbenchService(gateway,
                new BusinessDataScopeValidator(), sessions, executor).snapshot(lease, identity(), null, null);

        String serialized = snapshot.toString();
        assertThat(serialized).doesNotContain("oa.example", "drop", "password", "accessToken", "tenant-1");
        assertThat(snapshot.notices().data().toString()).contains("公告");
        assertThat(snapshot.summary().data().toString()).contains("case_handle");
    }

    @Test
    void home_info_registers_avatar_only_after_a_trusted_oa_resource_response() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessResourceHandleRegistry resources = mock(BusinessResourceHandleRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.homeInfo(eq("tenant-1"), any())).thenReturn(
                new OaWorkbenchDtos.UserHomeInfo(
                        "user-1", "tenant-1", "律师", "https://oa.example/avatar.png", Map.of()));
        when(gateway.fetchResource(
                eq("tenant-1"), any(), eq("https://oa.example/avatar.png")))
                .thenReturn(new OaWorkbenchGateway.RemoteResource("image/png", new byte[]{1, 2, 3}));
        when(resources.register(any(), eq(lease), eq("image/png"), any(), eq(Duration.ofMinutes(5))))
                .thenReturn(new BusinessResourceHandleRegistry.ResourceDescriptor(
                        "avatar-handle", "image/png", 3, Instant.now(), Instant.now().plusSeconds(300)));

        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor(sessions), resources);

        BusinessWorkbenchDtos.HomeInfoEnvelope result = service.homeInfo(lease, identity());

        assertThat(result.section().data().toString())
                .contains("avatar-handle")
                .doesNotContain("oa.example");
        verify(gateway).fetchResource(
                eq("tenant-1"), any(), eq("https://oa.example/avatar.png"));
        verify(resources).register(any(), eq(lease), eq("image/png"), any(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void navigation_is_a_local_allowlist_and_does_not_forward_remote_urls() {
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessWorkbenchService service = new BusinessWorkbenchService(
                mock(OaWorkbenchGateway.class), new BusinessDataScopeValidator(),
                sessions, mock(OaAuthenticatedRequestExecutor.class));

        TrustedBusinessIdentity projected = new TrustedBusinessIdentity(
                "reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 7,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("*"),
                Set.of("/case", "/team", "https://evil.example"));
        BusinessWorkbenchDtos.NavigationEnvelope navigation = service.navigation(lease, projected);

        assertThat(navigation.items()).extracting(BusinessWorkbenchDtos.NavigationTarget::path)
                .containsExactly("/", "/case", "/team");
        assertThat(navigation.items()).allSatisfy(item -> assertThat(item.path()).startsWith("/"));
        assertThat(navigation.generation()).isEqualTo(3);
    }

    @Test
    void snapshotDoesNotDowngradeFatalAuthenticationFailureIntoASectionError() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(executor.execute(eq(lease), eq(OaAuthenticatedRequestExecutor.RequestKind.READ), any()))
                .thenThrow(new CompletionException(
                        com.wzx.babiq.server.business.oa.session.OaRemoteRequestException
                                .authenticationExpired(401)));

        BusinessWorkbenchService service = new BusinessWorkbenchService(
                gateway, new BusinessDataScopeValidator(), sessions, executor);

        assertThatThrownBy(() -> service.snapshot(lease, identity(), null, null))
                .isInstanceOf(com.wzx.babiq.server.business.oa.session.OaRemoteRequestException.class);
    }

    private static ReadyOaSessionLease lease() {
        return new ReadyOaSessionLease("auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1",
                "2", 3, "credential-1", 1, Instant.now());
    }

    private static TrustedBusinessIdentity identity() {
        return new TrustedBusinessIdentity("reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 7,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("*"));
    }

    private static BusinessWorkbenchDtos.PageRequest pageRequest(String kind, String filterKey,
                                                                  Object filterValue) {
        return new BusinessWorkbenchDtos.PageRequest(
                kind, "TEAM", "team-9", "role-x", 7, 13, Map.of(filterKey, filterValue));
    }

    private static Stream<Arguments> invalidScalarFilterValues() {
        return Stream.of(
                Arguments.of("blank string", "   "),
                Arguments.of("unknown string", "unknown"),
                Arguments.of("non-integral decimal string", "2.0"),
                Arguments.of("non-decimal string", "0x2"),
                Arguments.of("Long overflow", Long.MAX_VALUE),
                Arguments.of("integral Double", 2.0d),
                Arguments.of("NaN", Double.NaN),
                Arguments.of("positive infinity", Double.POSITIVE_INFINITY));
    }

    private static OaAuthenticatedRequestExecutor executor(BusinessOaSessionRegistry sessions) {
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        when(executor.execute(any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            OaAuthenticatedRequestExecutor.CredentialOperation<Object> operation = invocation.getArgument(2);
            return operation.execute("server-token".toCharArray());
        });
        return executor;
    }
}
