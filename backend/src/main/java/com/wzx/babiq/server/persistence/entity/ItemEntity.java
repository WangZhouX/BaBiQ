package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * `bq_items` 表对应的 MyBatis-Plus 实体。
 *
 * <p>Item 是桌面端聊天流中真正展示的最小单元，可以是用户消息、Agent 消息、工具调用或文件变更。
 * P2-1 暂时以 JSON payload 保留协议原文，后续新增 item 类型时不必马上改表。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_items")
public class ItemEntity {

    /** 数据库内部自增主键；不暴露给前端。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层 itemId；用于前端幂等更新和后端去重。 */
    @TableField("item_id")
    private String itemId;

    /** 所属 threadId；按会话恢复历史时使用。 */
    @TableField("thread_id")
    private String threadId;

    /** 所属 turnId；按运行回合回放时使用。 */
    @TableField("turn_id")
    private String turnId;

    /** item 类型，例如 userMessage、assistantMessage、toolCall。 */
    private String type;

    /** item 在会话内的顺序号，保证恢复后的显示顺序稳定。 */
    @TableField("sequence_no")
    private Integer sequenceNo;

    /** item 原始 JSON payload；由协议层写入，由历史接口原样读取。 */
    @TableField("payload_json")
    private String payloadJson;

    /** item 状态，例如 streaming、completed、failed。 */
    private String status;

    /** 创建时间，使用 Instant 字符串保存。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间；流式 item 合并时会刷新。 */
    @TableField("updated_at")
    private String updatedAt;
}
