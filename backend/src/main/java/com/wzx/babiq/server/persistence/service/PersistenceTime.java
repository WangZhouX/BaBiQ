package com.wzx.babiq.server.persistence.service;

import java.time.Instant;

/**
 * 持久化层的时间转换工具。
 *
 * <p>SQLite 没有真正的 Instant 类型，BaBiQ 统一把时间保存为 ISO-8601 字符串。这个工具把转换集中起来，
 * 避免每个 repository 都手写 null 判断和 `Instant.parse`。</p>
 */
public final class PersistenceTime {

    private PersistenceTime() {
    }

    /**
     * 把 Instant 转成 SQLite 中保存的字符串。
     *
     * @param instant 业务层时间，可为空
     * @return ISO-8601 字符串；输入为空时返回 null
     */
    public static String write(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * 把 SQLite 字符串还原成 Instant。
     *
     * @param value 数据库中的时间字符串，可为空
     * @return Instant；输入为空或空白时返回 null
     */
    public static Instant read(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
