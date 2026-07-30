package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;

import java.util.Map;
import java.util.Set;

/** Request-shape validation for client-visible workbench scopes. */
public final class BusinessDataScopeValidator {
    public void validate(BusinessWorkbenchDtos.PageRequest request) {
        if (request == null || request.kind() == null || !Set.of("CASE", "APPOINTMENT", "COUNSELOR_SERVICE", "VISIT").contains(request.kind())) throw new IllegalArgumentException("invalid kind");
        if (request.scope() == null || !Set.of("ALL", "PERSONAL", "TEAM").contains(request.scope())) throw new IllegalArgumentException("invalid scope");
        if (request.pageNo() < 1 || request.pageNo() > BusinessWorkbenchPayloadLimits.MAX_PAGE_NUMBER
                || request.pageSize() < 1 || request.pageSize() > BusinessWorkbenchPayloadLimits.MAX_ITEMS) {
            throw new IllegalArgumentException("invalid page");
        }
        validateIdentifier("teamId", request.teamId());
        validateIdentifier("roleCode", request.roleCode());
        validateFilters(request.kind(), request.filters());
        if (!"TEAM".equals(request.scope())) {
            if (request.teamId() != null || request.roleCode() != null) throw new IllegalArgumentException("team fields require TEAM scope");
            return;
        }
        if (request.teamId() == null || request.teamId().isBlank()) throw new IllegalArgumentException("TEAM requires teamId");
    }

    private static void validateIdentifier(String name, String value) {
        if (value != null && value.length() > BusinessWorkbenchPayloadLimits.MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(name + " is too long");
        }
    }

    private static void validateFilters(String kind, Map<String, Object> filters) {
        String key = switch (kind) {
            case "CASE" -> "status";
            case "APPOINTMENT" -> "consultMode";
            case "COUNSELOR_SERVICE" -> "serviceStatus";
            case "VISIT" -> "visitObj";
            default -> throw new IllegalArgumentException("invalid kind");
        };
        if (filters == null || filters.isEmpty()) return;
        if (filters.size() != 1 || !filters.containsKey(key)) throw new IllegalArgumentException("unknown filter");
        int value = integer(filters.get(key));
        boolean valid = switch (kind) {
            case "CASE" -> value == 1 || value == 2;
            case "APPOINTMENT" -> value == 0 || value == 1 || value == 2;
            case "COUNSELOR_SERVICE" -> value == 0 || value == 1;
            case "VISIT" -> value == 1 || value == 2;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("invalid filter");
    }

    private static int integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid filter");
            return (int) number;
        }
        if (value instanceof String text && !text.isBlank() && text.matches("-?[0-9]+")) {
            try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException("invalid filter");
    }
}
