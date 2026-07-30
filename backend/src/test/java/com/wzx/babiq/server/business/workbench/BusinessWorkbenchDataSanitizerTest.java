package com.wzx.babiq.server.business.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessWorkbenchDataSanitizerTest {
    @Test
    void keeps_only_stable_profile_fields_and_removes_nested_secrets_and_remote_urls() {
        Object value = BusinessWorkbenchDataSanitizer.sanitize(
                "profile",
                Map.of(
                        "nickname", "Lawyer",
                        "tenantId", "tenant-secret",
                        "avatar", "https://oa.example/avatar.png",
                        "membershipStatus", "ACTIVE",
                        "internal", Map.of("password", "password-canary", "debug", "must-drop"),
                        "teams", List.of(Map.of("name", "Team A", "accessToken", "token-canary"))));

        assertThat(value).isEqualTo(Map.of(
                "nickname", "Lawyer",
                "membershipStatus", "ACTIVE"));
        assertThat(value.toString()).doesNotContain("tenant-secret", "oa.example", "password-canary", "token-canary", "must-drop");
    }

    @Test
    void keeps_workbench_schedule_shape_without_forwarding_unknown_remote_fields() {
        Object value = BusinessWorkbenchDataSanitizer.sanitize(
                "schedule",
                Map.of(
                        "count", 2,
                        "day", List.of(Map.of("id", "s-1", "title", "会见", "at", "10:00", "tenantId", "drop")),
                        "remoteUrl", "https://oa.example/schedule",
                        "implementationDetail", "drop"));

        assertThat(value).isEqualTo(Map.of(
                "count", 2,
                "day", List.of(Map.of("id", "s-1", "title", "会见", "at", "10:00"))));
    }

    @Test
    void keeps_real_oa_month_and_day_array_contracts_without_identity_or_file_fields() {
        Object month = BusinessWorkbenchDataSanitizer.sanitizeScheduleMonth(
                List.of(Map.of(
                        "schDate", "2026-07-29",
                        "schCount", 2,
                        "tenantId", "tenant-canary")));
        Object day = BusinessWorkbenchDataSanitizer.sanitizeScheduleDay(
                List.of(Map.of(
                        "time", "上午",
                        "allDay", 0,
                        "dayList", List.of(Map.of(
                                "id", "schedule-1",
                                "schTitle", "客户会议",
                                "schTime", "2026-07-29 10:00:00",
                                "schTypeTitle", "hearing",
                                "color", "#216DFF",
                                "schEmergeDegree", 3,
                                "repetition", 2,
                                "expiredDays", 4,
                                "finished", 1,
                                "accessToken", "token-canary")))));

        assertThat(month).isEqualTo(List.of(
                new com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleMonthEntry(
                        "2026-07-29", 2)));
        assertThat(day).isEqualTo(List.of(
                new com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayGroup(
                        "上午",
                        false,
                        List.of(new com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayItem(
                                "schedule-1",
                                "客户会议",
                                "2026-07-29 10:00:00",
                                true,
                                "hearing",
                                "#216DFF",
                                3,
                                2,
                                4)))));
        assertThat(day.toString()).contains(
                "typeTitle=hearing",
                "color=#216DFF",
                "priority=3",
                "repetition=2",
                "expiredDays=4");
        assertThat(day.toString()).doesNotContain("file-canary", "token-canary");
    }
    @Test
    void drops_remote_shortcut_icons_and_unknown_section_fields_by_default() {
        Object shortcut = BusinessWorkbenchDataSanitizer.sanitize(
                "shortcuts",
                List.of(Map.of(
                        "id", "shortcut-1",
                        "title", "案件",
                        "icon", "https://oa.example/icon.svg",
                        "path", "/case")));

        assertThat(shortcut).isEqualTo(List.of(Map.of(
                "id", "shortcut-1",
                "title", "案件",
                "path", "/case")));

        Object unknown = BusinessWorkbenchDataSanitizer.sanitize(
                "future-section",
                Map.of("remoteUrl", "https://oa.example/future", "implementationDetail", "drop"));
        assertThat(unknown).isEqualTo(Map.of());
    }

    @Test
    void shortcut_navigation_uses_only_the_lawyer_sidebar_allowlist_and_normalizes_home_aliases() {
        Object value = BusinessWorkbenchDataSanitizer.sanitize(
                "shortcuts",
                List.of(
                        Map.of("id", "home", "title", "首页", "path", "/index/unfinished"),
                        Map.of("id", "case", "title", "案件", "path", "/case"),
                        Map.of("id", "appointment", "title", "预约", "path", "/appointment"),
                        Map.of("id", "visit", "title", "拜访", "path", "/visit"),
                        Map.of("id", "schedule", "title", "日程", "path", "/schedule")));

        assertThat(value).isEqualTo(List.of(
                Map.of("id", "home", "title", "首页", "path", "/"),
                Map.of("id", "case", "title", "案件", "path", "/case"),
                Map.of("id", "appointment", "title", "预约"),
                Map.of("id", "visit", "title", "拜访"),
                Map.of("id", "schedule", "title", "日程")));
    }

    @Test
    void bounds_text_collections_and_recursive_payloads() {
        String oversized = "x".repeat(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH + 100);
        List<Map<String, Object>> notices = IntStream.range(0, BusinessWorkbenchPayloadLimits.MAX_ITEMS + 20)
                .mapToObj(index -> Map.<String, Object>of("id", index, "title", oversized))
                .toList();

        Object value = BusinessWorkbenchDataSanitizer.sanitize("notices", notices);

        assertThat(value).isInstanceOf(List.class);
        List<?> bounded = (List<?>) value;
        assertThat(bounded).hasSize(BusinessWorkbenchPayloadLimits.MAX_ITEMS);
        assertThat(bounded.get(0).toString()).hasSizeLessThanOrEqualTo(
                BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH + 20);

        Map<String, Object> deep = Map.of("value", Map.of("value", Map.of("value", Map.of("value", Map.of(
                "value", Map.of("value", Map.of("value", Map.of("value", Map.of("value", "drop")))))))));
        Object deepResult = BusinessWorkbenchDataSanitizer.sanitize("profile", deep);
        assertThat(deepResult.toString()).doesNotContain("drop");
    }
}
