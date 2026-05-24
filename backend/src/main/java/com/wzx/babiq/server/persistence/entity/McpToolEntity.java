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
 * `bq_mcp_tools` 表对应的 MyBatis-Plus 实体。
 *
 * <p>工具列表由 MCP server 的 listTools 返回，BaBiQ 只保存描述和 schema，
 * 不保存工具输出。真实工具调用仍由运行记录 `bq_tool_calls` 审计。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_mcp_tools")
public class McpToolEntity {

    /** 数据库内部自增主键；不暴露给协议层。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具所属 MCP server id。 */
    @TableField("server_id")
    private String serverId;

    /** MCP server 原始工具名。 */
    @TableField("tool_name")
    private String toolName;

    /** BaBiQ 内部命名空间工具名，例如 mcp.local-filesystem.read_file。 */
    @TableField("namespaced_name")
    private String namespacedName;

    /** 工具描述，供模型和设置页理解用途。 */
    private String description;

    /** MCP 工具 input schema JSON。 */
    @TableField("schema_json")
    private String schemaJson;

    /** 是否启用；P2-6 默认随 server 工具列表启用。 */
    private Boolean enabled;

    /** 工具列表刷新时间，ISO-8601 字符串。 */
    @TableField("updated_at")
    private String updatedAt;
}
