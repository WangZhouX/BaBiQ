package com.wzx.babiq.server.api.dto;

/**
 * runtime/item/remove 返回值。
 *
 * @param itemId 被隐藏的运行态 item id
 * @param type item 协议类型
 * @param status 软移除后的 item 状态
 * @param removed 是否已经隐藏
 */
public record RuntimeItemRemoveResult(
        String itemId,
        String type,
        String status,
        boolean removed
) {
}
