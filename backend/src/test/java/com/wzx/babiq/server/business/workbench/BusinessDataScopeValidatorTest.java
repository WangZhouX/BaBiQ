package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDataScopeValidatorTest {
    private final BusinessDataScopeValidator validator = new BusinessDataScopeValidator();

    @Test
    void validatesTeamRequestShapeWithoutProcessScopedAuthorizationState() {
        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "TEAM", "team-1", "case_viewer", 1, 20, Map.of("status", 1));
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(request.withRoleCode(null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request.withTeamId(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTeamArgumentsForAllAndPersonal() {
        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "ALL", "team-1", null, 1, 20, Map.of());
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesFilterKeysAndEnumsPerBusinessKind() {
        assertThatCode(() -> validator.validate(request("CASE", Map.of("status", 1)))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request("CASE", Map.of("status", "2")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request("CASE", Map.of("status", 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(request("CASE", Map.of("consultMode", 1))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> validator.validate(request("APPOINTMENT", Map.of("consultMode", 0)))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request("APPOINTMENT", Map.of("consultMode", "2")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request("APPOINTMENT", Map.of("consultMode", 3))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> validator.validate(request("COUNSELOR_SERVICE", Map.of("serviceStatus", 1)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request("COUNSELOR_SERVICE", Map.of("serviceStatus", 2))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> validator.validate(request("VISIT", Map.of("visitObj", 2)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(request("VISIT", Map.of("visitObj", 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_oversized_recursive_filter_payload_before_remote_use() {
        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int index = 0; index < 9; index++) oversized.put("filter-" + index, 1);
        assertThatThrownBy(() -> validator.validate(request("CASE", oversized)))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> nested = new LinkedHashMap<>();
        Map<String, Object> current = nested;
        for (int index = 0; index < 10; index++) {
            Map<String, Object> next = new LinkedHashMap<>();
            current.put("nested", next);
            current = next;
        }
        current.put("status", 1);
        assertThatThrownBy(() -> validator.validate(request("CASE", nested)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_page_numbers_above_payload_limit() {
        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "ALL", null, null, BusinessWorkbenchPayloadLimits.MAX_PAGE_NUMBER + 1, 20, Map.of());

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_team_id_longer_than_identifier_limit() {
        String oversizedTeamId = "t".repeat(BusinessWorkbenchPayloadLimits.MAX_IDENTIFIER_LENGTH + 1);
        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "TEAM", oversizedTeamId, "case_viewer", 1, 20, Map.of("status", 1));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("teamId is too long");
    }

    @Test
    void rejects_role_code_longer_than_identifier_limit() {
        String oversizedRoleCode = "r".repeat(BusinessWorkbenchPayloadLimits.MAX_IDENTIFIER_LENGTH + 1);
        BusinessWorkbenchDtos.PageRequest request = new BusinessWorkbenchDtos.PageRequest(
                "CASE", "TEAM", "team-1", oversizedRoleCode, 1, 20, Map.of("status", 1));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roleCode is too long");
    }

    private static BusinessWorkbenchDtos.PageRequest request(String kind, Map<String, Object> filters) {
        return new BusinessWorkbenchDtos.PageRequest(kind, "ALL", null, null, 1, 20, filters);
    }
}
