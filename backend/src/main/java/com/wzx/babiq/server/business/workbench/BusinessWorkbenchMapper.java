package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Converts untrusted OA rows to bounded, stable desktop rows. */
public final class BusinessWorkbenchMapper {
    private BusinessWorkbenchMapper() {}
    public static BusinessWorkbenchDtos.PageResult page(String kind, OaWorkbenchDtos.PageResult remote) {
        List<BusinessWorkbenchDtos.PageRow> rows = remote.items().stream()
                .limit(BusinessWorkbenchPayloadLimits.MAX_ITEMS).map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>();
            copyScalar(safe, row, "id", "title", "applicationNumber", "categoriesName", "scheduleName");
            switch (kind) {
                case "CASE" -> {
                    copyScalar(safe, row,
                            "caseName", "status", "caseLevel", "lawFirmName", "createTime", "acceptTime",
                            "closeTime", "archiveTime", "closeStatus", "archiveDesc", "caseNo",
                            "caseCategory", "caseReason", "agentProcess", "partyCount",
                            "lawFirmRelationStatus");
                    copyNestedMap(safe, row, "tenant", "name");
                    copyNestedList(safe, row, "teamDatas", "teamName", "name", "roleName", "roleCode");
                    copyNestedList(safe, row, "parties", "name");
                }
                case "APPOINTMENT" -> copyScalar(safe, row,
                        "name", "consultMode", "causeAction", "appointLocation", "remark", "createTime");
                case "COUNSELOR_SERVICE" -> copyScalar(safe, row,
                        "serviceTitle", "serviceObjectType", "serviceObjectName", "serviceStatus",
                        "totalServiceCount", "serviceStartDate", "serviceEndDate", "createTime");
                case "VISIT" -> copyScalar(safe, row,
                        "visitItem", "visitObj", "visitObjName", "scheduleId", "visitTime", "visitDay",
                        "scheduleName", "createTime");
                default -> throw new IllegalArgumentException("invalid workbench kind");
            }
            return new BusinessWorkbenchDtos.PageRow(identifier(row.get("id")), text(row.get("applicationNumber")),
                    text(row.get("categoriesName")), text(row.get("scheduleName")), text(row.get("title")), safe);
        }).toList();
        return new BusinessWorkbenchDtos.PageResult(BusinessWorkbenchPayloadLimits.total(remote.total()),
                BusinessWorkbenchPayloadLimits.pageNumber(remote.pageNo()),
                BusinessWorkbenchPayloadLimits.pageSize(remote.pageSize()), rows);
    }
    private static void copyScalar(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (!source.containsKey(key)) continue;
            Object raw = source.get(key);
            if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) continue;
            Object value = BusinessWorkbenchPayloadLimits.value(raw);
            if (value != null) target.put(key, value);
        }
    }

    private static void copyNestedMap(Map<String, Object> target, Map<String, Object> source,
                                      String key, String... allowedKeys) {
        if (!(source.get(key) instanceof Map<?, ?> value)) return;
        Map<String, Object> safe = whitelist(value, allowedKeys);
        if (!safe.isEmpty()) target.put(key, Map.copyOf(safe));
    }

    private static void copyNestedList(Map<String, Object> target, Map<String, Object> source,
                                       String key, String... allowedKeys) {
        if (!(source.get(key) instanceof Collection<?> values)) return;
        List<Map<String, Object>> safe = values.stream()
                .limit(BusinessWorkbenchPayloadLimits.MAX_ITEMS)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(value -> whitelist(value, allowedKeys))
                .filter(value -> !value.isEmpty())
                .map(Map::copyOf)
                .toList();
        if (!safe.isEmpty()) target.put(key, safe);
    }

    private static Map<String, Object> whitelist(Map<?, ?> source, String... allowedKeys) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            Object raw = source.get(key);
            if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) continue;
            Object value = BusinessWorkbenchPayloadLimits.value(raw);
            if (value != null) safe.put(key, value);
        }
        return safe;
    }
    private static String identifier(Object value) { return BusinessWorkbenchPayloadLimits.identifier(value); }
    private static String text(Object value) { return BusinessWorkbenchPayloadLimits.text(value); }
}
