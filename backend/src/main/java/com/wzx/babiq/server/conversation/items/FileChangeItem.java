package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文件变更 item。
 *
 * <p>该类型用于描述 Agent 读取、写入、补丁或删除文件的意图与结果。P1-1 只
 * 保留基础字段,避免提前绑定尚未实现的 diff 结构。</p>
 *
 * @param id item 标识
 * @param type 固定为 fileChange
 * @param action 文件动作
 * @param path 文件路径
 * @param status 文件动作状态
 * @param contentPreview 内容预览
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileChangeItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String action,
        @JsonProperty(required = true) String path,
        @JsonProperty(required = true) String status,
        String contentPreview
) implements ThreadItem {
}
