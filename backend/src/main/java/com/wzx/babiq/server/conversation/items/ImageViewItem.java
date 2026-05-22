package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 图片查看占位 item。
 *
 * <p>未来多模态能力需要在对话流中展示图片查看动作。P1-1 只定义协议壳,不
 * 引入图片处理逻辑。</p>
 *
 * @param id item 标识
 * @param type 固定为 imageView
 */
public record ImageViewItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type
) implements ThreadItem {

    /**
     * 创建图片查看占位 item。
     *
     * @param id item 标识
     */
    public ImageViewItem(String id) {
        this(id, "imageView");
    }
}
