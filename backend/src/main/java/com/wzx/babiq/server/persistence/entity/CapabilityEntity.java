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
 * `bq_capabilities` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存模型可发现能力的轻量元数据。真实工具 schema 仍保留在 Spring AI ToolCallback，
 * 这里保存 hash 和 searchText，便于审计和按需搜索。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_capabilities")
public class CapabilityEntity {

    /** 数据库内部自增主键；仅供持久化层使用。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定能力 id；Planner、搜索事件和桌面端都使用它定位能力。 */
    @TableField("capability_id")
    private String capabilityId;

    /** 能力类型，LOCAL_TOOL、MCP_TOOL 或 SKILL。 */
    private String type;

    /** 能力命名空间，用于区分 local、MCP server 和 Skill 来源。 */
    private String namespace;

    /** 工具或 Skill 短名称。 */
    private String name;

    /** 桌面端展示名称。 */
    @TableField("display_name")
    private String displayName;

    /** 给模型和用户看的短说明，不包含敏感参数值。 */
    private String description;

    /** 来源 id；local 工具为 local，MCP 为 serverId，Skill 为目录 id。 */
    @TableField("source_id")
    private String sourceId;

    /** 工具 schema 或 Skill 正文摘要 hash，用于识别变更。 */
    @TableField("schema_hash")
    private String schemaHash;

    /** 搜索索引文本，供 fallback 词法搜索读取。 */
    @TableField("search_text")
    private String searchText;

    /** 暴露模式，VISIBLE、DEFERRED 或 DISABLED。 */
    @TableField("exposure_mode")
    private String exposureMode;

    /** 用户是否启用该能力；false 时不会搜索也不会暴露给模型。 */
    private Boolean enabled;

    /** 最近扫描发现该能力的时间，ISO-8601 文本。 */
    @TableField("last_seen_at")
    private String lastSeenAt;

    /** 记录创建时间，ISO-8601 文本。 */
    @TableField("created_at")
    private String createdAt;

    /** 最近更新时间，ISO-8601 文本。 */
    @TableField("updated_at")
    private String updatedAt;
}
