package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("bq_application_action_events")
public class ApplicationActionEventEntity {
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    @TableField("execution_id") private String executionId;
    @TableField("event_sequence") private Long eventSequence;
    @TableField("event_type") private String eventType;
    @TableField("from_status") private String fromStatus;
    @TableField("to_status") private String toStatus;
    @TableField("payload_summary_redacted") private String payloadSummaryRedacted;
    @TableField("late_result") private Boolean lateResult;
    @TableField("occurred_at") private String occurredAt;
}
