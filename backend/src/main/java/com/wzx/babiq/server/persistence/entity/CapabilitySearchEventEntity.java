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
 * `bq_capability_search_events` 表对应的 MyBatis-Plus 实体。
 *
 * <p>每次 `tool_search` 或设置页搜索都会写入该表，用来解释某个延迟能力为何在后续轮次可见。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_capability_search_events")
public class CapabilitySearchEventEntity {

    /** 数据库内部自增主键；仅供持久化层使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 协议层搜索事件 id。 */
    @TableField("event_id")
    private String eventId;

    /** 来源 thread id；设置页手动搜索时可为空。 */
    @TableField("thread_id")
    private String threadId;

    /** 来源 turn id；设置页手动搜索时可为空。 */
    @TableField("turn_id")
    private String turnId;

    /** 搜索词或模型提出的能力需求。 */
    @TableField("query_text")
    private String queryText;

    /** 搜索策略；P3-5a 之后新事件固定写入 LUCENE，旧数据库记录可能保留历史策略值。 */
    private String strategy;

    /** 返回候选数量。 */
    @TableField("result_count")
    private Integer resultCount;

    /** 最终返回或装配的能力 id JSON 数组。 */
    @TableField("selected_capability_ids_json")
    private String selectedCapabilityIdsJson;

    /** 被过滤、禁用或未命中的能力 id JSON 数组。 */
    @TableField("rejected_capability_ids_json")
    private String rejectedCapabilityIdsJson;

    /** 事件创建时间。 */
    @TableField("created_at")
    private String createdAt;
}
