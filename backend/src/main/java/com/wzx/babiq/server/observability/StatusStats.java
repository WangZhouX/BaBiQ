package com.wzx.babiq.server.observability;

/**
 * turn 状态分布统计。
 *
 * @param status turn 状态，例如 COMPLETED、FAILED、INTERRUPTED。
 * @param turns 该状态下的 turn 数。
 */
public record StatusStats(String status, long turns) {
}
