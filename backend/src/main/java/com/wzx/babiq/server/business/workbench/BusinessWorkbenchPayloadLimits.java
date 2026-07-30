package com.wzx.babiq.server.business.workbench;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Shared bounds for values crossing the business workbench RPC boundary. */
final class BusinessWorkbenchPayloadLimits {
    static final int MAX_ITEMS = 100;
    static final long MAX_TOTAL = 1_000_000L;
    static final int MAX_PAGE_NUMBER = 1_000_000;
    static final int MAX_TEXT_LENGTH = 1_024;
    static final int MAX_IDENTIFIER_LENGTH = 256;
    static final int MAX_DEPTH = 8;

    private BusinessWorkbenchPayloadLimits() {
    }

    static String text(Object value) {
        if (value == null) return null;
        return text(String.valueOf(value), MAX_TEXT_LENGTH);
    }

    static String identifier(Object value) {
        if (value == null) return null;
        return text(String.valueOf(value), MAX_IDENTIFIER_LENGTH);
    }

    static String text(String value, int maxLength) {
        if (value == null) return null;
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    static long total(long value) {
        return Math.max(0, Math.min(MAX_TOTAL, value));
    }

    static int pageNumber(int value) {
        return Math.max(1, Math.min(MAX_PAGE_NUMBER, value));
    }

    static int pageSize(int value) {
        return Math.max(1, Math.min(MAX_ITEMS, value));
    }

    static Object value(Object value) {
        return value(value, 0);
    }

    private static Object value(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String string) return text(string);
        if (depth >= MAX_DEPTH) return null;
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            Map<String, Object> bounded = new java.util.LinkedHashMap<>();
            entries.stream().limit(MAX_ITEMS).forEach(entry -> {
                String key = text(String.valueOf(entry.getKey()));
                Object item = value(entry.getValue(), depth + 1);
                if (key != null && item != null) bounded.put(key, item);
            });
            return bounded;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> bounded = new ArrayList<>(Math.min(collection.size(), MAX_ITEMS));
            collection.stream().limit(MAX_ITEMS).map(item -> value(item, depth + 1))
                    .filter(item -> item != null).forEach(bounded::add);
            return List.copyOf(bounded);
        }
        if (value.getClass().isArray()) return null;
        return null;
    }
}
