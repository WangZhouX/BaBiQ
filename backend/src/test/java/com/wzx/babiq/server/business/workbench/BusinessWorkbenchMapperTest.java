package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessWorkbenchMapperTest {
    @Test
    void mapsRemoteRowsToStableDesktopFieldsOnly() {
        OaWorkbenchDtos.PageResult remote = new OaWorkbenchDtos.PageResult(3, 1, 10,
                List.of(Map.of("id", 7, "applicationNumber", "A-7", "categoriesName", "劳动争议",
                        "tenantId", "must-not-leak", "relatedIds", List.of("secret"))));

        BusinessWorkbenchDtos.PageResult mapped = BusinessWorkbenchMapper.page("CASE", remote);

        assertThat(mapped.total()).isEqualTo(3);
        assertThat(mapped.items()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("7");
            assertThat(row.applicationNumber()).isEqualTo("A-7");
            assertThat(row.categoriesName()).isEqualTo("劳动争议");
            assertThat(row.toString()).doesNotContain("must-not-leak", "relatedIds");
        });
    }

    @Test
    void bounds_remote_rows_text_and_page_metadata() {
        String oversized = "x".repeat(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH + 100);
        List<Map<String, Object>> rows = IntStream.range(0, BusinessWorkbenchPayloadLimits.MAX_ITEMS + 20)
                .mapToObj(index -> Map.<String, Object>of("id", index, "title", oversized))
                .toList();
        OaWorkbenchDtos.PageResult remote = new OaWorkbenchDtos.PageResult(Long.MAX_VALUE, 0, 10_000, rows);

        BusinessWorkbenchDtos.PageResult mapped = BusinessWorkbenchMapper.page("CASE", remote);

        assertThat(mapped.items()).hasSize(BusinessWorkbenchPayloadLimits.MAX_ITEMS);
        assertThat(mapped.total()).isEqualTo(BusinessWorkbenchPayloadLimits.MAX_TOTAL);
        assertThat(mapped.pageNo()).isEqualTo(1);
        assertThat(mapped.pageSize()).isEqualTo(BusinessWorkbenchPayloadLimits.MAX_ITEMS);
        assertThat(mapped.items().get(0).title()).hasSize(BusinessWorkbenchPayloadLimits.MAX_TEXT_LENGTH);
    }

    @Test
    void maps_each_business_kind_to_its_real_web_fields_without_cross_kind_or_secret_values() {
        Map<String, Object> remoteRow = Map.ofEntries(
                Map.entry("id", "row-1"),
                Map.entry("name", "预约人"),
                Map.entry("consultMode", 2),
                Map.entry("causeAction", "合同"),
                Map.entry("appointLocation", "会议室"),
                Map.entry("remark", "携带材料"),
                Map.entry("serviceTitle", "常法服务"),
                Map.entry("serviceObjectName", "顾问单位"),
                Map.entry("serviceStatus", 1),
                Map.entry("totalServiceCount", 3),
                Map.entry("visitItem", "客户回访"),
                Map.entry("visitObj", 2),
                Map.entry("visitObjName", "客户甲"),
                Map.entry("visitTime", "2026-07-29"),
                Map.entry("visitDay", 1),
                Map.entry("scheduleName", "回访日程"),
                Map.entry("createTime", "2026-07-28"),
                Map.entry("accessToken", "must-not-leak"));
        OaWorkbenchDtos.PageResult remote = new OaWorkbenchDtos.PageResult(1, 1, 20, List.of(remoteRow));

        assertThat(BusinessWorkbenchMapper.page("APPOINTMENT", remote).items().getFirst().values())
                .containsEntry("name", "预约人")
                .containsEntry("consultMode", 2)
                .containsEntry("appointLocation", "会议室")
                .doesNotContainKeys("serviceTitle", "visitItem", "accessToken");
        assertThat(BusinessWorkbenchMapper.page("COUNSELOR_SERVICE", remote).items().getFirst().values())
                .containsEntry("serviceTitle", "常法服务")
                .containsEntry("serviceObjectName", "顾问单位")
                .containsEntry("totalServiceCount", 3)
                .doesNotContainKeys("name", "visitItem", "accessToken");
        assertThat(BusinessWorkbenchMapper.page("VISIT", remote).items().getFirst().values())
                .containsEntry("visitItem", "客户回访")
                .containsEntry("visitObjName", "客户甲")
                .containsEntry("scheduleName", "回访日程")
                .doesNotContainKeys("name", "serviceTitle", "accessToken");
    }

    @Test
    void case_mapping_whitelists_nested_shared_fields_and_drops_secrets_file_ids_canaries_and_remote_logo_urls() {
        Map<String, Object> row = Map.ofEntries(
                Map.entry("id", "case-1"),
                Map.entry("caseName", "合同争议"),
                Map.entry("logo", "https://oa.example.com/logo.png?accessToken=logo-canary"),
                Map.entry("tenant", Map.of(
                        "name", "惠太律所",
                        "accessToken", "nested-access-canary",
                        "fileIds", List.of("oa-file-canary"),
                        "nested", Map.of("canary", "deep-tenant-canary"))),
                Map.entry("teamDatas", List.of(Map.of(
                        "teamName", "一组",
                        "roleName", "负责人",
                        "refreshToken", "nested-refresh-canary",
                        "fileIds", List.of("team-file-canary"),
                        "nested", Map.of("canary", "deep-team-canary")))),
                Map.entry("lawFirmRelationStatus", 1),
                Map.entry("accessToken", "must-not-leak"));

        Map<String, Object> values = BusinessWorkbenchMapper.page(
                "CASE", new OaWorkbenchDtos.PageResult(1, 1, 20, List.of(row)))
                .items().getFirst().values();

        assertThat(values)
                .doesNotContainKey("logo")
                .containsEntry("tenant", Map.of("name", "惠太律所"))
                .containsEntry("teamDatas", List.of(Map.of("teamName", "一组", "roleName", "负责人")))
                .containsEntry("lawFirmRelationStatus", 1)
                .doesNotContainKey("accessToken");
        assertThat(values.toString()).doesNotContain(
                "oa.example.com", "nested-access-canary", "nested-refresh-canary",
                "oa-file-canary", "team-file-canary", "deep-tenant-canary", "deep-team-canary");
    }
}
