package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchException;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessAttachmentBatchRecord;
import com.wzx.babiq.server.business.upload.BusinessAttachmentFileIdStore;
import com.wzx.babiq.server.business.upload.BusinessAttachmentRepository;
import com.wzx.babiq.server.business.upload.BusinessAttachmentTicketService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BusinessScheduleServiceTest {
    @Test
    void month_uses_year_month_without_inventing_a_day() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleCount(
                eq("tenant-1"), any(), eq("2026-07"), eq("PERSONAL"), isNull(), eq(false)))
                .thenReturn(List.of(Map.of("schDate", "2026-07-29", "schCount", 1)));

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleMonthEnvelope result = service.month(
                lease, identity(), new BusinessWorkbenchDtos.ScheduleQuery(
                        "2026-07", "PERSONAL", null, false, null));

        assertThat(result.days()).containsExactly(
                new BusinessWorkbenchDtos.ScheduleMonthEntry("2026-07-29", 1));
        verify(gateway).scheduleCount(
                eq("tenant-1"), any(), eq("2026-07"), eq("PERSONAL"), isNull(), eq(false));
    }

    @Test
    void reads_day_through_ready_lease_and_keeps_identity_envelope() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleDay(eq("tenant-1"), any(), eq("2026-07-27"), eq("PERSONAL"), isNull(), eq(true), isNull()))
                .thenReturn(List.of(Map.of(
                        "time", "上午",
                        "allDay", 0,
                        "dayList", List.of(Map.of(
                                "id", "schedule-1",
                                "schTitle", "today",
                                "schTime", "2026-07-27 10:00:00",
                                "finished", 0)))));

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor);
        BusinessWorkbenchDtos.ScheduleDayEnvelope result = service.day(lease, identity(),
                new BusinessWorkbenchDtos.ScheduleQuery("2026-07-27", "PERSONAL", null, true, null));

        assertThat(result.identityEpoch()).isEqualTo(7);
        assertThat(result.groups().getFirst().items().getFirst().title()).isEqualTo("today");
        verify(gateway).scheduleDay(eq("tenant-1"), any(), eq("2026-07-27"), eq("PERSONAL"), isNull(), eq(true), isNull());
    }

    @Test
    void completion_is_a_write_and_create_rejects_assignment_to_other_user_for_regular_member() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.setScheduleCompletion(eq("tenant-1"), any(), eq("schedule-1"), eq(true))).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor);

        BusinessWorkbenchDtos.ScheduleCompletionResult completed = service.setCompletion(lease, identity(), "schedule-1", true);

        assertThat(completed.completed()).isTrue();
        verify(gateway).setScheduleCompletion(eq("tenant-1"), any(), eq("schedule-1"), eq(true));

        BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-1", "TEAM", "team-1", "other-user", "title", "type-1", "2026-07-27 10:00:00",
                false, 2, "content", List.of(), List.of(), null);
        assertThatThrownBy(() -> service.create(lease, identityWithoutWildcard(), request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(gateway, never()).createSchedule(any(), any(), any());
    }

    @Test
    void relation_type_and_duplicate_sort_ids_are_rejected_before_remote_call() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);

        assertThatThrownBy(() -> service.relationOptions(lease, identity(), "UNKNOWN", "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateSort(lease, identity(), "SHORTCUT", List.of("1", "1"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(gateway, never()).createSchedule(any(), any(), any());
    }

    @Test
    void service_projects_fail_closed_unless_record_is_in_current_service_options() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "service-1", "serviceTitle", "顾问服务")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("service-1"), eq("诉讼")))
                .thenReturn(List.of(Map.of("id", "project-1", "projectName", "诉讼项目")));
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        assertThat(service.serviceProjects(lease, identity(), "service-1", "诉讼").items())
                .singleElement().satisfies(item ->
                        assertThat(item).containsEntry("id", "project-1"));
        assertThatThrownBy(() -> service.serviceProjects(lease, identity(), "service-2", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("service record is not authorized");
        verify(gateway, never()).serviceProjects(eq("tenant-1"), any(), eq("service-2"), any());
    }

    @Test
    void team_service_projects_authorize_record_in_the_requested_team_scope() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), eq("team-1"), isNull()))
                .thenReturn(List.of(Map.of("id", "record-1", "serviceTitle", "Team service")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("record-1"), isNull()))
                .thenReturn(List.of(Map.of("id", "project-1", "projectName", "Appeal")));
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        BusinessWorkbenchDtos.RelationOptionsEnvelope result = service.serviceProjects(
                lease, identity(), "record-1", null, "team-1");

        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item).containsEntry("id", "project-1"));
        verify(gateway).relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), eq("team-1"), isNull());
        verify(gateway, never()).relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), isNull(), isNull());
    }

    @Test
    void attachment_prepare_uses_team_member_userId_and_authorizes_service_record_then_leaf() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("TEAM"), eq("team-1")))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.scheduleMembers(eq("tenant-1"), any(), eq("team-1")))
                .thenReturn(List.of(Map.of("id", "member-1", "userId", "user-1")));
        when(gateway.relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), eq("team-1"), isNull()))
                .thenReturn(List.of(Map.of("id", "record-1", "serviceTitle", "Team service")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("record-1"), isNull()))
                .thenReturn(List.of(Map.of("id", "project-1", "projectName", "Appeal")));
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        service.authorizeAttachmentPrepare(
                lease, identity(), "TEAM", "team-1", "type-1",
                "SERVICE", "project-1", "record-1", 0);

        verify(gateway).scheduleMembers(eq("tenant-1"), any(), eq("team-1"));
        verify(gateway).relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), eq("team-1"), isNull());
        verify(gateway).serviceProjects(eq("tenant-1"), any(), eq("record-1"), isNull());
    }

    @Test
    void attachment_prepare_rejects_service_leaf_from_a_different_selected_record() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(
                eq("tenant-1"), any(), eq("SERVICE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "record-1"), Map.of("id", "record-2")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("record-1"), isNull()))
                .thenReturn(List.of(Map.of("id", "project-1")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("record-2"), isNull()))
                .thenReturn(List.of(Map.of("id", "project-2")));
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        assertThatThrownBy(() -> service.authorizeAttachmentPrepare(
                lease, identity(), "PERSONAL", null, "type-1",
                "SERVICE", "project-2", "record-1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attachment parent is not currently authorized");
        verify(gateway, never()).serviceProjects(eq("tenant-1"), any(), eq("record-2"), any());
    }

    @Test
    void sort_revision_is_isolated_by_authenticated_identity_and_generation() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease firstLease = lease();
        ReadyOaSessionLease secondLease = new ReadyOaSessionLease(
                "auth-2", "desktop-2", "session-2", "ws-2", "user-2", "tenant-2",
                "2", 9, "credential-2", 1, Instant.now());
        when(sessions.isCurrent(firstLease)).thenReturn(true);
        when(sessions.isCurrent(secondLease)).thenReturn(true);
        when(gateway.shortcuts(any(), any())).thenReturn(List.of(
                Map.of("id", "shortcut-1", "enabled", true, "path", "/case")));
        when(gateway.updateSort(any(), any(), eq(1), eq(List.of("shortcut-1")))).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        BusinessWorkbenchDtos.MutationEnvelope first = service.updateSort(
                firstLease, identity(), "SHORTCUT", List.of("shortcut-1"), 0);
        BusinessWorkbenchDtos.MutationEnvelope second = service.updateSort(
                secondLease, identity("auth-2", "ws-2", "desktop-2", "session-2",
                        "user-2", "tenant-2", 11), "SHORTCUT", List.of("shortcut-1"), 0);

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(1);
        verify(gateway, times(2)).updateSort(any(), any(), eq(1), eq(List.of("shortcut-1")));
    }

    @Test
    void shortcut_sort_uses_only_enabled_safe_desktop_targets_as_the_canonical_set() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.shortcuts(eq("tenant-1"), any())).thenReturn(List.of(
                Map.of("id", "enabled", "enabled", true, "path", "/case"),
                Map.of("id", "disabled", "enabled", false, "path", "/customer"),
                Map.of("id", "disabled-string", "enabled", "false", "path", "/team"),
                Map.of("id", "unsafe", "enabled", true, "path", "https://evil.example")));
        when(gateway.updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("enabled")))).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        BusinessWorkbenchDtos.MutationEnvelope result = service.updateSort(
                lease, identity(), "SHORTCUT", List.of("enabled"), 0);

        assertThat(result.revision()).isEqualTo(1);
        verify(gateway).updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("enabled")));
    }

    @Test
    void same_sort_revision_key_serializes_compare_remote_write_and_revision_increment() throws Exception {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        CountDownLatch firstReadEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        CountDownLatch secondReadEntered = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(gateway.shortcuts(eq("tenant-1"), any())).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                firstReadEntered.countDown();
                if (!releaseFirstRead.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("first read was not released");
                }
            } else {
                secondReadEntered.countDown();
            }
            return List.of(Map.of("id", "shortcut-1", "enabled", true, "path", "/case"));
        });
        when(gateway.updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("shortcut-1")))).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> sortOutcome(service, lease));
            assertThat(firstReadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> sortOutcome(service, lease));

            boolean overlappedRemoteRead = secondReadEntered.await(500, TimeUnit.MILLISECONDS);
            releaseFirstRead.countDown();
            List<Object> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(overlappedRemoteRead).isFalse();
            assertThat(outcomes.stream().filter(BusinessWorkbenchDtos.MutationEnvelope.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(value -> value instanceof IllegalStateException failure
                    && "BUSINESS_CONFLICT".equals(failure.getMessage()))).hasSize(1);
            verify(gateway, times(1)).updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("shortcut-1")));
        } finally {
            releaseFirstRead.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rejected_remote_sort_does_not_advance_revision_and_expected_zero_can_retry() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.shortcuts(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "shortcut-1", "enabled", true, "path", "/case")));
        when(gateway.updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("shortcut-1"))))
                .thenReturn(false, true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        assertThatThrownBy(() -> service.updateSort(
                lease, identity(), "SHORTCUT", List.of("shortcut-1"), 0))
                .isInstanceOf(OaWorkbenchException.class)
                .hasMessage("REMOTE_PROTOCOL_ERROR");

        BusinessWorkbenchDtos.MutationEnvelope retry = service.updateSort(
                lease, identity(), "SHORTCUT", List.of("shortcut-1"), 0);
        assertThat(retry.revision()).isEqualTo(1);
        verify(gateway, times(2)).updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("shortcut-1")));
    }

    @Test
    void sort_revision_storage_is_bounded_across_generation_rotation_and_cannot_roll_back() throws Exception {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        when(sessions.isCurrent(any())).thenReturn(true);
        when(gateway.shortcuts(eq("tenant-1"), any())).thenReturn(
                List.of(Map.of("id", "shortcut-1", "enabled", true, "path", "/case")));
        when(gateway.updateSort(eq("tenant-1"), any(), eq(1), eq(List.of("shortcut-1")))).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        for (long generation = 1; generation <= 100; generation++) {
            ReadyOaSessionLease rotated = new ReadyOaSessionLease(
                    "auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1",
                    "2", generation, "credential-" + generation, 1, Instant.now());
            assertThat(service.updateSort(
                    rotated, identity(), "SHORTCUT", List.of("shortcut-1"), 0).revision()).isEqualTo(1);
        }

        java.lang.reflect.Field revisionsField = BusinessScheduleService.class.getDeclaredField("revisions");
        revisionsField.setAccessible(true);
        Map<?, ?> revisions = (Map<?, ?>) revisionsField.get(service);
        assertThat(revisions.keySet().stream()
                .filter(key -> "SortRevisionKey".equals(key.getClass().getSimpleName())))
                .hasSize(1);

        ReadyOaSessionLease stale = new ReadyOaSessionLease(
                "auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1",
                "2", 99, "credential-99", 1, Instant.now());
        assertThatThrownBy(() -> service.updateSort(
                stale, identity(), "SHORTCUT", List.of("shortcut-1"), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");
    }

    @Test
    void sort_rejects_more_than_one_hundred_ids_and_oversized_identifiers_before_remote_read() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        assertThatThrownBy(() -> service.updateSort(lease, identity(), "SHORTCUT",
                IntStream.rangeClosed(0, 100).mapToObj(index -> "id-" + index).toList(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateSort(lease, identity(), "SHORTCUT",
                List.of("x".repeat(257)), 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(gateway, never()).shortcuts(any(), any());
    }

    @Test
    void bounds_schedule_form_option_count_and_text() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        String oversized = "x".repeat(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH + 100);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("TEAM"), eq("team-1")))
                .thenReturn(IntStream.range(0, BusinessWorkbenchPayloadLimits.MAX_ITEMS + 20)
                        .mapToObj(index -> Map.<String, Object>of("id", "type-" + index, "name", oversized))
                        .toList());
        when(gateway.scheduleMembers(eq("tenant-1"), any(), eq("team-1")))
                .thenReturn(IntStream.range(0, BusinessWorkbenchPayloadLimits.MAX_ITEMS + 20)
                        .mapToObj(index -> Map.<String, Object>of(
                                "id", "member-" + index,
                                "userId", index == 0 ? "user-1" : "user-" + index,
                                "name", oversized))
                        .toList());
        when(gateway.isTeamLeaderOrAdmin(eq("tenant-1"), any(), eq("team-1"))).thenReturn(true);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleFormEnvelope result = service.form(lease, identity(), "TEAM", "team-1");

        assertThat(result.types()).hasSize(BusinessWorkbenchPayloadLimits.MAX_ITEMS);
        assertThat(result.members()).hasSize(BusinessWorkbenchPayloadLimits.MAX_ITEMS);
        assertThat(result.types().get(0).get("name").toString())
                .hasSize(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH);
    }

    @Test
    void form_keeps_required_safe_option_fields_and_team_assignment_uses_current_leader_admin_status() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("TEAM"), eq("team-1")))
                .thenReturn(List.of(Map.of(
                        "id", "type-1", "type", "开庭", "color", "#335CFF",
                        "projects", List.of(Map.of("id", "project-1", "projectName", "一审")))));
        when(gateway.scheduleMembers(eq("tenant-1"), any(), eq("team-1")))
                .thenReturn(List.of(
                        Map.of("id", "member-1", "userId", "user-1", "userName", "当前律师",
                                "avatar", "safeOpaqueAvatarHandle123456"),
                        Map.of("id", "member-2", "userId", "user-2", "userName", "其他律师",
                                "avatar", "safeOpaqueAvatarHandle654321")));
        when(gateway.isTeamLeaderOrAdmin(eq("tenant-1"), any(), eq("team-1"))).thenReturn(false);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleFormEnvelope result =
                service.form(lease, identity(), "TEAM", "team-1");

        assertThat(result.types().getFirst())
                .containsKeys("id", "type", "color", "projects");
        assertThat(result.members()).singleElement().satisfies(member ->
                assertThat(member).containsEntry("userId", "user-1")
                        .containsEntry("userName", "当前律师")
                        .containsKey("avatar"));
    }

    @Test
    void team_create_requires_an_assignee_and_forwards_valid_repetition() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        assertThatThrownBy(() -> service.create(lease, identity(),
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-no-assignee", "TEAM", "team-1", null, "庭审", "type-1",
                        "2026-07-27 10:00:00", false, 2, "准备材料", List.of(30),
                        List.of(), null, null, null, 0, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TEAM requires assigneeUserId");
        verify(gateway, never()).createSchedule(any(), any(), any());

        when(gateway.isTeamLeaderOrAdmin(eq("tenant-1"), any(), eq("team-1"))).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("TEAM"), eq("team-1")))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.scheduleMembers(eq("tenant-1"), any(), eq("team-1")))
                .thenReturn(List.of(
                        Map.of("id", "member-1", "userId", "user-1"),
                        Map.of("id", "member-2", "userId", "user-2")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-repeat", "TEAM", "team-1", "user-2", "庭审", "type-1",
                        "2026-07-27 10:00:00", false, 2, "准备材料", List.of(30),
                        List.of(), null, null, null, 0, 3);

        service.create(lease, identity(), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        assertThat(payload.getValue()).containsEntry("repetition", 3);
    }

    @Test
    void personal_create_forces_trusted_identity_as_remote_assignee() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenReturn(Map.of("id", "schedule-1"));
        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor(sessions));

        service.create(lease, identity(), createRequest("op-personal-owner", null, null));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        assertThat(payload.getValue()).containsEntry("userId", "user-1");
    }

    @Test
    void personal_create_rejects_client_assignee_different_from_trusted_identity() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-personal-drift", "PERSONAL", null, "user-2", "Meeting", "type-1",
                        "2026-07-27 10:00:00", false, 2, null, List.of(),
                        List.of(), null, null, null, 0, 0);

        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("personal assignee must match authenticated user");
        verifyNoInteractions(gateway);
    }

    @Test
    void schedule_create_rejects_attachment_batch_when_scope_drifts() {
        assertAttachmentBindingDriftRejected(
                "PERSONAL", null, "type-1",
                "TEAM", "team-1", "type-1");
    }

    @Test
    void schedule_create_rejects_attachment_batch_when_team_drifts() {
        assertAttachmentBindingDriftRejected(
                "TEAM", "team-1", "type-1",
                "TEAM", "team-2", "type-1");
    }

    @Test
    void schedule_create_rejects_attachment_batch_when_schedule_type_drifts() {
        assertAttachmentBindingDriftRejected(
                "PERSONAL", null, "type-1",
                "PERSONAL", null, "type-2");
    }

    @Test
    void all_day_create_allows_real_oa_negative_reminder_and_forces_midnight_payload() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenReturn(Map.of("id", "schedule-1"));
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-all-day", "PERSONAL", null, null, "All day", "type-1",
                        "2026-07-27 10:45:00", true, 2, null, List.of(-540, 240),
                        List.of(), null, null, null, 0, 0);

        service.create(lease, identity(), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("schTime", "2026-07-27 00:00:00")
                .containsEntry("schRemTimes", "-540,240");
    }

    @Test
    void non_all_day_create_rejects_negative_reminder_before_remote_calls() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-invalid-reminder", "PERSONAL", null, null, "Meeting", "type-1",
                        "2026-07-27 10:45:00", false, 2, null, List.of(-540),
                        List.of(), null, null, null, 0, 0);

        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid schedule reminder");
        verify(gateway, never()).createSchedule(any(), any(), any());
    }

    @Test
    void create_rejects_zero_duplicate_and_unknown_negative_reminders_before_remote_calls() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        List<List<Integer>> invalid = List.of(
                List.of(0),
                List.of(30, 30),
                List.of(-541));

        for (int index = 0; index < invalid.size(); index++) {
            BusinessWorkbenchDtos.ScheduleCreateRequest request =
                    new BusinessWorkbenchDtos.ScheduleCreateRequest(
                            "op-invalid-reminder-" + index, "PERSONAL", null, null, "Meeting", "type-1",
                            "2026-07-27 00:00:00", true, 2, null, invalid.get(index),
                            List.of(), null, null, null, 0, 0);
            assertThatThrownBy(() -> service.create(lease, identity(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid schedule reminder");
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void create_reauthorizes_service_record_and_leaf_project_before_remote_write() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("SERVICE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "record-1", "serviceTitle", "常年顾问")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("record-1"), isNull()))
                .thenReturn(List.of(Map.of("id", "project-1", "projectName", "合同审查")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        Map<String, Object> relation = Map.of(
                "relationType", "SERVICE",
                "relationId", "project-1",
                "relationTitle", "合同审查",
                "parentId", "record-1");
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-service", "PERSONAL", null, null, "合同审查", "type-1",
                        "2026-07-27 10:00:00", false, 2, null, List.of(),
                        List.of(relation), null, null, null, 0, 0);

        service.create(lease, identity(), request);

        verify(gateway).relationOptions(eq("tenant-1"), any(), eq("SERVICE"), isNull(), isNull(), isNull());
        verify(gateway).serviceProjects(eq("tenant-1"), any(), eq("record-1"), isNull());
        verify(gateway).createSchedule(eq("tenant-1"), any(), any());

        Map<String, Object> unauthorized = new java.util.LinkedHashMap<>(relation);
        unauthorized.put("relationId", "project-2");
        BusinessWorkbenchDtos.ScheduleCreateRequest rejected =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-service-rejected", "PERSONAL", null, null, "合同审查", "type-1",
                        "2026-07-27 10:00:00", false, 2, null, List.of(),
                        List.of(unauthorized), null, null, null, 0, 0);
        assertThatThrownBy(() -> service.create(lease, identity(), rejected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schedule relation is not currently authorized");
        verify(gateway, times(1)).createSchedule(eq("tenant-1"), any(), any());
    }

    @Test
    void unauthorized_relation_is_rejected_before_remote_write_or_attachment_consumption() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(attachments.canConsumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-unauthorized"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-other")));
        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor(sessions), attachments);

        assertThatThrownBy(() -> service.create(
                lease, identity(), createRequest("op-unauthorized", "batch-1", "case-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schedule relation is not currently authorized");

        verify(gateway, never()).createSchedule(any(), any(), any());
        verify(attachments, never()).beginScheduleCreate(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(attachments, never()).consumeForScheduleCreate(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejects_oversized_schedule_description_reminders_and_relations_before_remote_write() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));

        assertThatThrownBy(() -> service.create(lease, identity(),
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-long-description", "PERSONAL", null, null,
                        "title", "type-1", "2026-07-27 10:00:00", false, 2,
                        "x".repeat(201), List.of(), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);

        List<Integer> tooManyReminders = IntStream.range(0, 21)
                .boxed().toList();
        BusinessWorkbenchDtos.ScheduleCreateRequest tooMany = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-reminders", "PERSONAL", null, null,
                "title", "type-1", "2026-07-27 10:00:00", false, 2,
                "content", tooManyReminders, List.of(), null);
        assertThatThrownBy(() -> service.create(lease, identity(), tooMany))
                .isInstanceOf(IllegalArgumentException.class);

        List<Map<String, Object>> tooManyRelations = IntStream.range(0, 9)
                .mapToObj(index -> Map.<String, Object>of("relationType", "CASE", "relationId", "case-" + index))
                .toList();
        BusinessWorkbenchDtos.ScheduleCreateRequest tooManyRelationsRequest = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-relations", "PERSONAL", null, null,
                "title", "type-1", "2026-07-27 10:00:00", false, 2,
                "content", List.of(), tooManyRelations, null);
        assertThatThrownBy(() -> service.create(lease, identity(), tooManyRelationsRequest))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void sends_only_bounded_relation_fields_to_remote_schedule_create() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));

        Map<String, Object> relation = new HashMap<>();
        relation.put("relationType", "CASE");
        relation.put("relationId", "case-1");
        relation.put("relationTitle", "x".repeat(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH + 20));
        relation.put("relationExtra", Map.of("nested", Map.of("secret", "must-not-forward")));
        relation.put("accessToken", "token-must-not-forward");
        BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-relation-fields", "PERSONAL", null, null,
                "title", "type-1", "2026-07-27 10:00:00", false, 2,
                "content", List.of(), List.of(relation), null);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        service.create(lease, identity(), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        Object relations = payload.getValue().get("relations");
        assertThat(relations).isEqualTo(List.of(Map.of(
                "relationType", 1,
                "relationId", "case-1",
                "relationTitle", "x".repeat(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH))));
        assertThat(payload.getValue().toString()).doesNotContain("must-not-forward", "token-must-not-forward");
    }

    @Test
    void does_not_stringify_structured_relation_title_into_remote_payload() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));

        Map<String, Object> relation = new HashMap<>();
        relation.put("relationType", "CASE");
        relation.put("relationId", "case-1");
        relation.put("relationTitle", Map.of("accessToken", "nested-token-must-not-forward"));
        BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-structured-relation-title", "PERSONAL", null, null,
                "title", "type-1", "2026-07-27 10:00:00", false, 2,
                "content", List.of(), List.of(relation), null);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        service.create(lease, identity(), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        assertThat(payload.getValue().get("relations")).isEqualTo(List.of(Map.of(
                "relationType", 1,
                "relationId", "case-1")));
        assertThat(payload.getValue().toString()).doesNotContain("nested-token-must-not-forward");
    }

    @Test
    void rejects_non_scalar_relation_ids_before_any_remote_call() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        List<Object> invalidIds = List.of(
                Map.of("accessToken", "map-token-must-not-forward"),
                List.of("list-token-must-not-forward"),
                new Object[]{"array-token-must-not-forward"},
                new Object(),
                Boolean.TRUE,
                12.5d);

        for (int index = 0; index < invalidIds.size(); index++) {
            Map<String, Object> relation = new HashMap<>();
            relation.put("relationType", "CASE");
            relation.put("relationId", invalidIds.get(index));
            BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                    "op-invalid-relation-id-" + index, "PERSONAL", null, null,
                    "title", "type-1", "2026-07-27 10:00:00", false, 2,
                    "content", List.of(), List.of(relation), null);

            assertThatThrownBy(() -> service.create(lease, identity(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid schedule relation");
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void normalizes_string_and_integer_relation_ids_for_remote_payload() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "123")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CUSTOMER"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "456")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));
        List<Map<String, Object>> relations = List.of(
                Map.of("relationType", "CASE", "relationId", "  123  "),
                Map.of("relationType", "CUSTOMER", "relationId", 456));
        BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-normalized-relation-ids", "PERSONAL", null, null,
                "title", "type-1", "2026-07-27 10:00:00", false, 2,
                "content", List.of(), relations, null);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        service.create(lease, identity(), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        assertThat(payload.getValue().get("relations")).isEqualTo(List.of(
                Map.of("relationType", 1, "relationId", "123"),
                Map.of("relationType", 2, "relationId", "456")));
    }

    @Test
    void maps_desktop_relation_type_symbols_to_oa_numeric_contract() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "101")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CUSTOMER"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "102")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("VISIT"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "103")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("SERVICE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "record-104")));
        when(gateway.serviceProjects(eq("tenant-1"), any(), eq("record-104"), isNull()))
                .thenReturn(List.of(Map.of("id", "104")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));
        List<Map<String, Object>> relations = List.of(
                Map.of("relationType", "CASE", "relationId", "101"),
                Map.of("relationType", "CUSTOMER", "relationId", "102"),
                Map.of("relationType", "VISIT", "relationId", "103"),
                Map.of("relationType", "SERVICE", "relationId", "104", "parentId", "record-104"));
        BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-relation-types", "PERSONAL", null, null,
                "title", "type-1", "2026-07-27 10:00:00", false, 2,
                "content", List.of(), relations, null);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        service.create(lease, identity(), request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).createSchedule(eq("tenant-1"), any(), payload.capture());
        assertThat(payload.getValue().get("relations")).isEqualTo(List.of(
                Map.of("relationType", 1, "relationId", "101"),
                Map.of("relationType", 2, "relationId", "102"),
                Map.of("relationType", 3, "relationId", "103"),
                Map.of("relationType", 4, "relationId", "104")));
    }

    @Test
    void rejects_non_string_blank_and_unknown_relation_types_before_any_remote_call() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        List<Object> invalidRelationTypes = java.util.Arrays.asList(
                new StringBuilder("CASE"), null, "   ", "UNKNOWN");

        for (int index = 0; index < invalidRelationTypes.size(); index++) {
            Map<String, Object> relation = new HashMap<>();
            relation.put("relationType", invalidRelationTypes.get(index));
            relation.put("relationId", "101");
            BusinessWorkbenchDtos.ScheduleCreateRequest request = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                    "op-invalid-relation-type-" + index, "PERSONAL", null, null,
                    "title", "type-1", "2026-07-27 10:00:00", false, 2,
                    "content", List.of(), List.of(relation), null);

            assertThatThrownBy(() -> service.create(lease, identity(), request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid schedule relation");
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void create_preflights_and_consumes_a_ready_attachment_batch_after_remote_success() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        OaAuthenticatedRequestExecutor executor = executor(sessions);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));
        when(attachments.canConsumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenReturn(true);
        when(attachments.consumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenReturn(true);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor, attachments);
        BusinessWorkbenchDtos.ScheduleCreateRequest request = createRequest("op-1", "batch-1", "case-1");

        BusinessWorkbenchDtos.MutationEnvelope result = service.create(lease, identity(), request);

        assertThat(result.refreshRequired()).isTrue();
        verify(attachments).canConsumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull());
        verify(gateway).createSchedule(eq("tenant-1"), any(), any());
        verify(attachments).consumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull());
    }

    @Test
    void create_rejects_an_unavailable_attachment_before_remote_write() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(attachments.canConsumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenReturn(false);

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions), attachments);

        assertThatThrownBy(() -> service.create(lease, identity(), createRequest("op-1", "batch-1", "case-1")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(gateway, never()).createSchedule(any(), any(), any());
    }

    @Test
    void create_is_idempotent_for_the_same_operation_and_rejects_a_different_payload() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.MutationEnvelope first = service.create(lease, identity(), createRequest("op-1", null, null));
        BusinessWorkbenchDtos.MutationEnvelope retry = service.create(lease, identity(), createRequest("op-1", null, null));

        assertThat(retry).isEqualTo(first);
        verify(gateway, times(1)).createSchedule(eq("tenant-1"), any(), any());
        BusinessWorkbenchDtos.ScheduleCreateRequest changed = new BusinessWorkbenchDtos.ScheduleCreateRequest(
                "op-1", "PERSONAL", null, null, "changed", "type-1", "2026-07-27 10:00:00",
                false, 2, "content", List.of(), List.of(), null);
        assertThatThrownBy(() -> service.create(lease, identity(), changed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void create_preserves_unknown_outcome_after_remote_write_failure_and_rejects_retry() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenThrow(OaRemoteRequestException.networkFailure(true));

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions));
        BusinessWorkbenchDtos.ScheduleCreateRequest request = createRequest("op-unknown", null, null);

        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(OaRemoteRequestException.class)
                .hasMessage("OA_OUTCOME_UNKNOWN");
        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_OPERATION_OUTCOME_UNKNOWN");
        verify(gateway, times(1)).createSchedule(eq("tenant-1"), any(), any());
    }

    @Test
    void production_create_claims_durable_in_flight_before_oa_and_terminalizes_each_outcome() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessScheduleOperationRepository operations = mock(BusinessScheduleOperationRepository.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(operations.claim(any(), any())).thenAnswer(invocation -> {
            BusinessScheduleOperationRepository.Request operation = invocation.getArgument(0);
            return new BusinessScheduleOperationRepository.Claim(
                    BusinessScheduleOperationRepository.Decision.WON,
                    new BusinessScheduleOperationRepository.Record(
                            operation.operationId(), operation.requestFingerprint(),
                            BusinessScheduleOperationRepository.State.IN_FLIGHT, null));
        });
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenReturn(Map.of("id", "schedule-1"));
        when(operations.complete(any(), any(), anyLong(), any())).thenReturn(true);
        BusinessScheduleService service = new BusinessScheduleService(
                gateway, sessions, executor(sessions), mock(BusinessAttachmentTicketService.class), operations);

        BusinessWorkbenchDtos.MutationEnvelope first =
                service.create(lease, identity(), createRequest("op-durable-success", null, null));

        InOrder successOrder = inOrder(operations, gateway);
        successOrder.verify(operations).claim(any(), any());
        successOrder.verify(gateway).createSchedule(eq("tenant-1"), any(), any());
        successOrder.verify(operations).complete(any(), any(), anyLong(), any());
        doAnswer(invocation -> {
            BusinessScheduleOperationRepository.Request operation = invocation.getArgument(0);
            return new BusinessScheduleOperationRepository.Claim(
                    BusinessScheduleOperationRepository.Decision.COMPLETED,
                    new BusinessScheduleOperationRepository.Record(
                            operation.operationId(), operation.requestFingerprint(),
                            BusinessScheduleOperationRepository.State.COMPLETED, first.revision()));
        }).when(operations).claim(any(), any());
        assertThat(service.create(
                lease, identity(), createRequest("op-durable-success", null, null))).isEqualTo(first);
        verify(gateway, times(1)).createSchedule(eq("tenant-1"), any(), any());

        reset(operations, gateway);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(operations.claim(any(), any())).thenAnswer(invocation -> {
            BusinessScheduleOperationRepository.Request operation = invocation.getArgument(0);
            return new BusinessScheduleOperationRepository.Claim(
                    BusinessScheduleOperationRepository.Decision.WON,
                    new BusinessScheduleOperationRepository.Record(
                            operation.operationId(), operation.requestFingerprint(),
                            BusinessScheduleOperationRepository.State.IN_FLIGHT, null));
        });
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenThrow(OaRemoteRequestException.networkFailure(true));
        when(operations.markOutcomeUnknown(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                lease, identity(), createRequest("op-durable-unknown", null, null)))
                .isInstanceOf(OaRemoteRequestException.class);
        verify(operations).markOutcomeUnknown(any(), any(), any());
        verify(operations, never()).markFailed(any(), any(), any());

        reset(operations, gateway);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(operations.claim(any(), any())).thenAnswer(invocation -> {
            BusinessScheduleOperationRepository.Request operation = invocation.getArgument(0);
            return new BusinessScheduleOperationRepository.Claim(
                    BusinessScheduleOperationRepository.Decision.WON,
                    new BusinessScheduleOperationRepository.Record(
                            operation.operationId(), operation.requestFingerprint(),
                            BusinessScheduleOperationRepository.State.IN_FLIGHT, null));
        });
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenThrow(OaRemoteRequestException.networkFailure(false));
        when(operations.markOutcomeUnknown(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                lease, identity(), createRequest("op-durable-failed", null, null)))
                .isInstanceOf(OaRemoteRequestException.class);
        verify(operations).markOutcomeUnknown(any(), any(), any());
        verify(operations, never()).markFailed(any(), any(), any());
    }

    @Test
    void completed_durable_replay_returns_before_consumed_attachment_preflight() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        BusinessScheduleOperationRepository operations = mock(BusinessScheduleOperationRepository.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(operations.claim(any(), any())).thenAnswer(invocation -> {
            BusinessScheduleOperationRepository.Request operation = invocation.getArgument(0);
            return new BusinessScheduleOperationRepository.Claim(
                    BusinessScheduleOperationRepository.Decision.COMPLETED,
                    new BusinessScheduleOperationRepository.Record(
                            operation.operationId(), operation.requestFingerprint(),
                            BusinessScheduleOperationRepository.State.COMPLETED, 41L));
        });
        BusinessScheduleService service = new BusinessScheduleService(
                gateway, sessions, executor(sessions), attachments, operations);
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                createRequest("op-completed-attachment", "batch-consumed", "case-1");

        BusinessWorkbenchDtos.MutationEnvelope replay = service.create(lease, identity(), request);

        assertThat(replay.revision()).isEqualTo(41L);
        verify(attachments, never()).canConsumeForScheduleCreate(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    void create_marks_attachment_outcome_unknown_after_gateway_invocation_even_for_definite_network_failure() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        BusinessAttachmentTicketService.ScheduleAttachmentConsumption consumption =
                mock(BusinessAttachmentTicketService.ScheduleAttachmentConsumption.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-failed")));
        when(attachments.canConsumeForScheduleCreate(
                eq("batch-failed"), any(), eq(lease), eq("op-failed"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-failed"), isNull()))
                .thenReturn(true);
        when(attachments.beginScheduleCreate(
                eq("batch-failed"), any(), eq(lease), eq("op-failed"), eq("user-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), isNull(), eq("case-failed"),
                isNull(), any()))
                .thenReturn(consumption);
        when(consumption.fileIds()).thenReturn(List.of("file-1".toCharArray()));
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenThrow(OaRemoteRequestException.networkFailure(false));

        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor(sessions), attachments);

        assertThatThrownBy(() -> service.create(
                lease, identity(), createRequest("op-failed", "batch-failed", "case-failed")))
                .isInstanceOf(OaRemoteRequestException.class);
        verify(attachments).finishScheduleCreate(
                consumption, BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        verify(gateway, times(1)).createSchedule(eq("tenant-1"), any(), any());
    }

    @Test
    void create_marks_attachment_failed_only_when_write_executor_rejects_before_gateway_invocation() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        BusinessScheduleOperationRepository operations = mock(BusinessScheduleOperationRepository.class);
        BusinessAttachmentTicketService.ScheduleAttachmentConsumption consumption =
                mock(BusinessAttachmentTicketService.ScheduleAttachmentConsumption.class);
        OaAuthenticatedRequestExecutor executor = mock(OaAuthenticatedRequestExecutor.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(executor.execute(any(), any(), any())).thenAnswer(invocation -> {
            OaAuthenticatedRequestExecutor.RequestKind kind = invocation.getArgument(1);
            if (kind == OaAuthenticatedRequestExecutor.RequestKind.WRITE) {
                throw new OaAuthenticatedRequestExecutor.StaleLeaseException();
            }
            @SuppressWarnings("unchecked")
            OaAuthenticatedRequestExecutor.CredentialOperation<Object> operation = invocation.getArgument(2);
            return operation.execute("server-token".toCharArray());
        });
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-failed")));
        when(attachments.canConsumeForScheduleCreate(
                eq("batch-failed"), any(), eq(lease), eq("op-pre-dispatch"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-failed"), isNull()))
                .thenReturn(true);
        when(attachments.beginScheduleCreate(
                eq("batch-failed"), any(), eq(lease), eq("op-pre-dispatch"), eq("user-1"),
                eq("PERSONAL"), isNull(), eq("type-1"), isNull(), eq("case-failed"),
                isNull(), any()))
                .thenReturn(consumption);
        when(consumption.fileIds()).thenReturn(List.of("file-1".toCharArray()));
        when(operations.claim(any(), any())).thenAnswer(invocation -> {
            BusinessScheduleOperationRepository.Request operation = invocation.getArgument(0);
            return new BusinessScheduleOperationRepository.Claim(
                    BusinessScheduleOperationRepository.Decision.WON,
                    new BusinessScheduleOperationRepository.Record(
                            operation.operationId(), operation.requestFingerprint(),
                            BusinessScheduleOperationRepository.State.IN_FLIGHT, null));
        });
        when(operations.markFailed(any(), any(), any())).thenReturn(true);
        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor, attachments, operations);

        assertThatThrownBy(() -> service.create(
                lease, identity(), createRequest("op-pre-dispatch", "batch-failed", "case-failed")))
                .isInstanceOf(OaAuthenticatedRequestExecutor.StaleLeaseException.class);

        verify(gateway, never()).createSchedule(any(), any(), any());
        verify(attachments).finishScheduleCreate(
                consumption, BusinessAttachmentTicketService.BatchStatus.FAILED);
        verify(operations, atLeastOnce()).markFailed(any(), any(), any());
        verify(operations, never()).markOutcomeUnknown(any(), any(), any());
    }

    @Test
    void create_preserves_unknown_outcome_when_attachment_consume_fails_after_remote_success() {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentTicketService attachments = mock(BusinessAttachmentTicketService.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq("PERSONAL"), isNull()))
                .thenReturn(List.of(Map.of("id", "type-1")));
        when(gateway.relationOptions(eq("tenant-1"), any(), eq("CASE"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(Map.of("id", "case-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any())).thenReturn(Map.of("id", "schedule-1"));
        when(attachments.canConsumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-consume"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenReturn(true);
        when(attachments.consumeForScheduleCreate(
                eq("batch-1"), any(), eq(lease), eq("op-consume"),
                eq("PERSONAL"), isNull(), eq("type-1"), eq("case-1"), isNull()))
                .thenThrow(new IllegalStateException("consume outcome unknown"));

        BusinessScheduleService service = new BusinessScheduleService(gateway, sessions, executor(sessions), attachments);
        BusinessWorkbenchDtos.ScheduleCreateRequest request = createRequest("op-consume", "batch-1", "case-1");

        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("consume outcome unknown");
        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_OPERATION_OUTCOME_UNKNOWN");
        verify(gateway, times(1)).createSchedule(eq("tenant-1"), any(), any());
    }

    private static BusinessWorkbenchDtos.ScheduleCreateRequest createRequest(String operation,
                                                                                String batchId,
                                                                                String parentId) {
        List<Map<String, Object>> relations = parentId == null
                ? List.of()
                : List.of(Map.of("relationType", "CASE", "relationId", parentId));
        return new BusinessWorkbenchDtos.ScheduleCreateRequest(
                operation, "PERSONAL", null, null, "title", "type-1", "2026-07-27 10:00:00",
                false, 2, "content", List.<Integer>of(), relations, batchId, parentId);
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

    private void assertAttachmentBindingDriftRejected(
            String batchScope,
            String batchTeamId,
            String batchTypeId,
            String requestScope,
            String requestTeamId,
            String requestTypeId) {
        OaWorkbenchGateway gateway = mock(OaWorkbenchGateway.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessAttachmentRepository repository = mock(BusinessAttachmentRepository.class);
        BusinessAttachmentFileIdStore fileIdStore = mock(BusinessAttachmentFileIdStore.class);
        ReadyOaSessionLease lease = lease();
        when(sessions.isCurrent(lease)).thenReturn(true);
        BusinessAttachmentBatchRecord batch = new BusinessAttachmentBatchRecord(
                "batch-binding", "desktop-1", "session-1", "auth-1", "tenant-1", 3,
                "SCHEDULE_CREATE", "op-binding", "user-1", batchScope, batchTeamId, batchTypeId,
                "CASE", "case-1", null, "0", "declaration-ref",
                BusinessAttachmentTicketService.BatchStatus.READY, "file-id-ref",
                Instant.now().plusSeconds(60), Instant.now(), Instant.now());
        when(repository.findBatch("batch-binding")).thenReturn(java.util.Optional.of(batch));
        when(repository.beginScheduleConsumption(
                any(), any(), any(), any(), any(), anyLong(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        BusinessAttachmentFileIdStore.StoredFileIds stored =
                mock(BusinessAttachmentFileIdStore.StoredFileIds.class);
        when(stored.values()).thenReturn(List.of("file-1".toCharArray()));
        when(fileIdStore.load("file-id-ref")).thenReturn(stored);
        when(repository.finishScheduleConsumption(any(), any(), any())).thenReturn(true);
        BusinessAttachmentTicketService attachments =
                new BusinessAttachmentTicketService(repository, fileIdStore);
        when(gateway.scheduleTypes(eq("tenant-1"), any(), eq(requestScope), eq(requestTeamId)))
                .thenReturn(List.of(Map.of("id", requestTypeId)));
        if ("TEAM".equals(requestScope)) {
            when(gateway.scheduleMembers(eq("tenant-1"), any(), eq(requestTeamId)))
                    .thenReturn(List.of(Map.of("id", "member-1", "userId", "user-1")));
        }
        when(gateway.relationOptions(
                eq("tenant-1"), any(), eq("CASE"), isNull(), eq(requestTeamId), isNull()))
                .thenReturn(List.of(Map.of("id", "case-1")));
        when(gateway.createSchedule(eq("tenant-1"), any(), any()))
                .thenReturn(Map.of("id", "schedule-1"));
        BusinessScheduleService service =
                new BusinessScheduleService(gateway, sessions, executor(sessions), attachments);
        BusinessWorkbenchDtos.ScheduleCreateRequest request =
                new BusinessWorkbenchDtos.ScheduleCreateRequest(
                        "op-binding", requestScope, requestTeamId,
                        "TEAM".equals(requestScope) ? "user-1" : null,
                        "Meeting", requestTypeId, "2026-07-27 10:00:00",
                        false, 2, null, List.of(),
                        List.of(Map.of("relationType", "CASE", "relationId", "case-1")),
                        "batch-binding", "case-1", "CASE", 0, 0);

        assertThatThrownBy(() -> service.create(lease, identity(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attachment batch is unavailable");
        verify(gateway, never()).createSchedule(any(), any(), any());
        verify(repository, never()).beginScheduleConsumption(
                any(), any(), any(), any(), any(), anyLong(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static Object sortOutcome(BusinessScheduleService service, ReadyOaSessionLease lease) {
        try {
            return service.updateSort(lease, identity(), "SHORTCUT", List.of("shortcut-1"), 0);
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static ReadyOaSessionLease lease() {
        return new ReadyOaSessionLease("auth-1", "desktop-1", "session-1", "ws-1", "user-1", "tenant-1",
                "2", 3, "credential-1", 1, Instant.now());
    }

    private static TrustedBusinessIdentity identity() {
        return new TrustedBusinessIdentity("reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 7,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("*"));
    }

    private static TrustedBusinessIdentity identity(String authSessionId, String webSocketSessionId,
                                                    String desktopInstanceId, String desktopSessionId,
                                                    String userId, String tenantId, long epoch) {
        return new TrustedBusinessIdentity("reservation-2", webSocketSessionId, desktopInstanceId, desktopSessionId,
                authSessionId, epoch, userId, tenantId, "2", Set.of("lawyer"), Set.of("*"));
    }

    private static TrustedBusinessIdentity identityWithoutWildcard() {
        return new TrustedBusinessIdentity("reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 7,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("schedule:read"));
    }
}
