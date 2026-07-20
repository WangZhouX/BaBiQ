package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wzx.babiq.server.attachment.AttachmentMetadata;

import java.util.List;

/**
 * 用户消息 item。
 *
 * <p>该类型记录用户在 turn/start 中提交的文本。P1-1 的 mock 流会把用户输入
 * 原样回显给桌面端,后续 UI 可以基于它渲染用户气泡。</p>
 *
 * @param id item 标识
 * @param type 固定为 userMessage
 * @param text 用户输入文本
 * @param attachments 本轮新选择并已验证的附件元数据；不包含重新引用的历史附件
 */
public record UserMessageItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String text,
        List<AttachmentMetadata> attachments
) implements ThreadItem {

    public UserMessageItem {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 兼容附件能力之前的构造调用点。 */
    public UserMessageItem(String id, String type, String text) {
        this(id, type, text, List.of());
    }

    /**
     * 创建标准用户消息 item。
     *
     * @param id item 标识
     * @param text 用户输入文本
     * @return type 已固定为 userMessage 的 item
     */
    public static UserMessageItem of(String id, String text) {
        return new UserMessageItem(id, "userMessage", text, List.of());
    }

    /** 创建带本轮新附件元数据的用户消息。 */
    public static UserMessageItem of(String id, String text, List<AttachmentMetadata> attachments) {
        return new UserMessageItem(id, "userMessage", text, attachments);
    }
}
