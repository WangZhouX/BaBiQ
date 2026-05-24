package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * `bq_turn_summaries` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表只在 turn 结束后写入，用于恢复桌面端“本轮成本反馈”以及后续 P2-5 本地可观测统计。
 * 成本使用 BigDecimal 映射，避免 double 在金额展示上产生不必要的二进制误差。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_turn_summaries")
public class TurnSummaryEntity {

    /** 数据库内部自增主键；不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属 turnId；一轮运行最多有一条摘要。 */
    @TableField("turn_id")
    private String turnId;

    /** 输入 token 数，来自模型 usage 或后端估算。 */
    @TableField("prompt_tokens")
    private Long promptTokens;

    /** 输出 token 数，来自模型 usage 或后端估算。 */
    @TableField("completion_tokens")
    private Long completionTokens;

    /** 美元成本估算。 */
    @TableField("cost_usd")
    private BigDecimal costUsd;

    /** 本轮耗时毫秒数。 */
    @TableField("duration_ms")
    private Long durationMs;

    /** 本轮工具调用次数。 */
    @TableField("tool_count")
    private Integer toolCount;

    /** 摘要生成时间。 */
    @TableField("created_at")
    private String createdAt;
}
