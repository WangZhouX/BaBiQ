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
 * `bq_work_unit_configs` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存工作容器的可编辑配置快照，供桌面端右侧 Inspector 回显。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_work_unit_configs")
public class WorkUnitConfigEntity {

    /** 数据库内部自增主键，不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属工作容器 id。 */
    @TableField("work_unit_id")
    private String workUnitId;

    /** 编排节点或团队成员配置 JSON。 */
    @TableField("config_json")
    private String configJson;

    /** 画布编排结构树 JSON；团队配置或旧版配置可为空。 */
    @TableField("structure_json")
    private String structureJson;

    /** 创建时间。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private String updatedAt;
}
