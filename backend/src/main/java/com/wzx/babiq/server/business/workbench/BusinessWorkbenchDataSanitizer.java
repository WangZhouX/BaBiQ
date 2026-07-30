package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts remote workbench values to the small, stable payload understood by the desktop client.
 * Unknown object types and fields are dropped instead of being reflected across the RPC boundary.
 */
public final class BusinessWorkbenchDataSanitizer {
    private static final Set<String> DENIED_KEY_PARTS = Set.of(
            "password", "token", "secret", "authorization", "tenantid", "userid", "fileid",
            "relatedids", "datarole", "moduleid", "traceid", "component", "tablename");
    private static final Set<String> LOCAL_PATHS = Set.of(
            "/", "/index", "/index/unfinished", "/lawoa", "/bpm", "/approval", "/case",
            "/administration", "/management", "/customer", "/cost", "/consultant", "/lawyer-admin",
            "/tools", "/team");

    private BusinessWorkbenchDataSanitizer() {
    }

    /** Converts the real OA month-count array into the stable typed desktop contract. */
    public static List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleMonthEntry>
    sanitizeScheduleMonth(Object value) {
        List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleMonthEntry> safe = new ArrayList<>();
        for (Object raw : values(value)) {
            if (safe.size() >= BusinessWorkbenchPayloadLimits.MAX_ITEMS || !(raw instanceof Map<?, ?> map)) break;
            String date = boundedText(value(map, "schDate"));
            Integer count = nonNegativeInt(value(map, "schCount"));
            if (date != null && count != null) {
                safe.add(new com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleMonthEntry(date, count));
            }
        }
        return List.copyOf(safe);
    }

    /** Converts the real OA grouped day array into the stable typed desktop contract. */
    public static List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayGroup>
    sanitizeScheduleDay(Object value) {
        List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayGroup> safe = new ArrayList<>();
        for (Object raw : values(value)) {
            if (safe.size() >= BusinessWorkbenchPayloadLimits.MAX_ITEMS || !(raw instanceof Map<?, ?> map)) break;
            List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayItem> items =
                    sanitizeScheduleDayItems(value(map, "dayList"));
            safe.add(new com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayGroup(
                    boundedText(value(map, "time")),
                    booleanValue(value(map, "allDay")),
                    items));
        }
        return List.copyOf(safe);
    }

    private static List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayItem>
    sanitizeScheduleDayItems(Object value) {
        List<com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayItem> safe = new ArrayList<>();
        for (Object raw : values(value)) {
            if (safe.size() >= BusinessWorkbenchPayloadLimits.MAX_ITEMS || !(raw instanceof Map<?, ?> map)) break;
            String id = firstText(map, "id", "schId");
            if (id == null) continue;
            safe.add(new com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos.ScheduleDayItem(
                    id,
                    firstText(map, "schTitle", "title"),
                    firstText(map, "schTime", "dateTime", "at"),
                    booleanValue(value(map, "finished")),
                    firstText(map, "schTypeTitle", "typeTitle"),
                    firstText(map, "color"),
                    nonNegativeInt(value(map, "schEmergeDegree")),
                    nonNegativeInt(value(map, "repetition")),
                    nonNegativeInt(value(map, "expiredDays"))));
        }
        return List.copyOf(safe);
    }

    private static List<?> values(Object value) {
        if (value instanceof Collection<?> collection) return List.copyOf(collection);
        if (value != null && value.getClass().isArray()) {
            int length = Math.min(Array.getLength(value), BusinessWorkbenchPayloadLimits.MAX_ITEMS);
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) values.add(Array.get(value, index));
            return values;
        }
        return List.of();
    }

    private static Object value(Map<?, ?> map, String key) {
        if (map.containsKey(key)) return map.get(key);
        String normalized = normalize(key);
        return map.entrySet().stream()
                .filter(entry -> normalize(String.valueOf(entry.getKey())).equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String value = boundedText(value(map, key));
            if (value != null) return value;
        }
        return null;
    }

    private static String boundedText(Object value) {
        if (value == null) return null;
        String text = BusinessWorkbenchPayloadLimits.text(String.valueOf(value));
        return text == null || text.isBlank() ? null : text;
    }

    private static Integer nonNegativeInt(Object value) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            return "1".equals(normalized) || "true".equals(normalized);
        }
        return false;
    }

    /** Sanitize one named workbench section before it is wrapped in a desktop DTO. */
    public static Object sanitize(String section, Object value) {
        if (value == null) return null;
        String context = normalize(section);
        if (value instanceof OaWorkbenchDtos.NoticePage page) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("total", BusinessWorkbenchPayloadLimits.total(page.total()));
            safe.put("pageNo", BusinessWorkbenchPayloadLimits.pageNumber(page.pageNo()));
            safe.put("pageSize", BusinessWorkbenchPayloadLimits.pageSize(page.pageSize()));
            safe.put("items", sanitizeCollection("noticeitem", page.items()));
            return safe;
        }
        if (value instanceof OaWorkbenchDtos.UserHomeInfo info) {
            Map<String, Object> safe = new LinkedHashMap<>();
            putText(safe, "nickname", info.nickname());
            putSafeHandle(safe, "avatar", info.avatar());
            Object values = sanitizeValue("profile", info.values());
            if (values instanceof Map<?, ?> map) {
                // Explicit fields win over values returned by the remote implementation.
                map.forEach((key, item) -> safe.putIfAbsent(String.valueOf(key), item));
            }
            return safe;
        }
        return sanitizeValue(context, value, 0);
    }

    private static Object sanitizeValue(String context, Object value) {
        return sanitizeValue(context, value, 0);
    }

    private static Object sanitizeValue(String context, Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String string) {
            return BusinessWorkbenchPayloadLimits.text(string);
        }
        if (depth >= BusinessWorkbenchPayloadLimits.MAX_DEPTH) return null;
        if (value instanceof Map<?, ?> map) return sanitizeMap(context, map, depth);
        if (value instanceof Collection<?> collection) return sanitizeCollection(childCollectionContext(context), collection, depth);
        if (value.getClass().isArray()) {
            int length = Math.min(Array.getLength(value), BusinessWorkbenchPayloadLimits.MAX_ITEMS);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object item = sanitizeValue(childCollectionContext(context), Array.get(value, i), depth + 1);
                if (item != null) values.add(item);
            }
            return values;
        }
        return null;
    }

    private static List<Object> sanitizeCollection(String context, Collection<?> values) {
        return sanitizeCollection(context, values, 0);
    }

    private static List<Object> sanitizeCollection(String context, Collection<?> values, int depth) {
        List<Object> safe = new ArrayList<>();
        if (values == null) return safe;
        for (Object value : values) {
            if (safe.size() >= BusinessWorkbenchPayloadLimits.MAX_ITEMS) break;
            Object item = sanitizeValue(context, value, depth + 1);
            if (item != null) safe.add(item);
        }
        return List.copyOf(safe);
    }

    private static Map<String, Object> sanitizeMap(String context, Map<?, ?> source) {
        return sanitizeMap(context, source, 0);
    }

    private static Map<String, Object> sanitizeMap(String context, Map<?, ?> source, int depth) {
        Map<String, Object> safe = new LinkedHashMap<>();
        Set<String> allowed = allowedKeys(context);
        // A new/unknown remote section must not become an accidental passthrough.
        if (allowed.isEmpty()) return safe;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String normalized = normalize(key);
            if (isDenied(normalized)) {
                if ("shortcutitem".equals(context) && "url".equals(normalized)) {
                    String path = safeLocalPath(entry.getValue());
                    if (path != null) safe.put("path", path);
                }
                continue;
            }
            if (!allowed.isEmpty() && !allowed.contains(normalized)) continue;
            if ("shortcutitem".equals(context) && "path".equals(normalized)) {
                String path = safeLocalPath(entry.getValue());
                if (path != null) safe.put("path", path);
                continue;
            }
            Object value = sanitizeValue(childContext(context, normalized), entry.getValue(), depth + 1);
            if (value == null) continue;
            if ("avatar".equals(normalized)) {
                if (value instanceof String handle && isSafeHandle(handle)) safe.put(key, handle);
            } else if ("icon".equals(normalized)) {
                if (value instanceof String handle && isSafeHandle(handle)) safe.put(key, handle);
            } else {
                safe.put(key, value);
            }
        }
        return safe;
    }

    private static Set<String> allowedKeys(String context) {
        return switch (context) {
            case "noticeitem" -> keys("id", "title", "releaseTime", "createTime", "isRead", "readState", "type", "typeId");
            case "shortcutitem" -> keys("id", "configCode", "configName", "name", "title", "path", "sort", "enabled", "icon");
            case "summaryitem" -> keys("id", "configCode", "configName", "sort", "enabled", "total", "stat");
            case "summarystat" -> keys("handling", "pendingArchive", "faceToFace", "wechat", "phone", "notStarted", "inProgress", "association", "consultantUnit");
            case "profile" -> keys("nickname", "name", "avatar", "membershipStatus", "member", "tenantName", "lawFirmName", "teamName", "tenantList", "teamList", "jobTitle");
            case "member" -> keys("exists", "expired", "memberType", "expireTime", "remainingDays");
            case "tenantitem" -> keys("id", "tenantName", "addressName", "detailedAddress", "jobTitle", "createTime");
            case "teamitem" -> keys("id", "name", "joinTime", "avatar");
            case "schedule" -> keys("count", "day", "groups", "list");
            case "schedulecountitem" -> keys("schDate", "schCount", "date", "count");
            case "schedulegroup" -> keys("id", "title", "at", "date", "dayList", "list");
            case "scheduleitem" -> keys("id", "dateTime", "count", "schTitle", "title", "schTypeTitle", "type", "typeName", "color", "schEmergeDegree", "finished", "schTime", "allDay", "repetition", "expiredDays", "userName", "name", "avatar", "time", "repeat", "tagLabel");
            default -> Set.of();
        };
    }

    private static String childContext(String context, String key) {
        return switch (context) {
            case "summaryitem" -> "stat".equals(key) ? "summarystat" : context;
            case "profile" -> switch (key) {
                case "member" -> "member";
                case "tenantlist" -> "tenantitem";
                case "teamlist" -> "teamitem";
                default -> context;
            };
            case "schedule" -> switch (key) {
                case "count" -> "schedulecountitem";
                case "day", "groups", "list" -> "schedulegroup";
                default -> context;
            };
            case "schedulegroup" -> switch (key) {
                case "daylist", "list" -> "scheduleitem";
                default -> context;
            };
            default -> context;
        };
    }

    private static String childCollectionContext(String context) {
        return switch (context) {
            case "notices" -> "noticeitem";
            case "shortcuts" -> "shortcutitem";
            case "summary" -> "summaryitem";
            case "teams" -> "teamitem";
            case "tenantlist" -> "tenantitem";
            case "teamlist" -> "teamitem";
            case "schedule" -> "scheduleitem";
            case "day", "groups", "list" -> "schedulegroup";
            case "daylist" -> "scheduleitem";
            default -> context;
        };
    }

    private static Set<String> keys(String... values) {
        return java.util.Arrays.stream(values).map(BusinessWorkbenchDataSanitizer::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean isDenied(String key) {
        if ("url".equals(key)) return true;
        return DENIED_KEY_PARTS.stream().anyMatch(key::contains);
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        String bounded = BusinessWorkbenchPayloadLimits.text(value);
        if (bounded != null && !bounded.isBlank()) target.put(key, bounded);
    }

    private static void putSafeHandle(Map<String, Object> target, String key, String value) {
        if (isSafeHandle(value)) target.put(key, value);
    }

    private static boolean isSafeHandle(String value) {
        return value != null && !value.isBlank() && value.length() <= 256
                && !value.contains("://") && !value.contains("/") && !value.contains("\\");
    }

    private static String safeLocalPath(Object value) {
        if (!(value instanceof String path) || !LOCAL_PATHS.contains(path)) return null;
        return Set.of("/index", "/index/unfinished").contains(path) ? "/" : path;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
