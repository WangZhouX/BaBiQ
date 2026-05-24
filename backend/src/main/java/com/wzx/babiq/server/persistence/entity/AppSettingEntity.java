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
 * `bq_app_settings` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存轻量全局设置，例如默认工作目录、沙箱模式和审批策略。P2-1 只提供通用 key/value
 * 持久化能力，P2-3 再把具体设置项和桌面端表单接入。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_app_settings")
public class AppSettingEntity {

    /** 数据库内部自增主键；业务层按 settingKey 访问，不直接使用该值。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设置 key，使用点分命名，例如 sandbox.mode。 */
    @TableField("setting_key")
    private String settingKey;

    /** 设置值，统一保存为字符串，由 valueType 决定解释方式。 */
    @TableField("setting_value")
    private String settingValue;

    /** 值类型，例如 string、boolean、number、json。 */
    @TableField("value_type")
    private String valueType;

    /** 设置更新时间。 */
    @TableField("updated_at")
    private String updatedAt;
}
