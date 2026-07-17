package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 业务桌面动作的展示型进度 item。
 *
 * <p>只允许安全摘要进入 UI；动作输入、身份、权限、原始 output 和 secret 永不进入该记录。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationActionItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String executionId,
        @JsonProperty(required = true) String actionId,
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) String risk,
        @JsonProperty(required = true) String status,
        String previewSummary,
        String errorCode,
        String errorSummary,
        Long durationMs
) implements ThreadItem {
}
