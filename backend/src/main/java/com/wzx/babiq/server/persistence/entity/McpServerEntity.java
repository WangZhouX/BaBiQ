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
 * `bq_mcp_servers` 表对应的 MyBatis-Plus 实体。
 *
 * <p>该表保存“后端受信任 MCP 配置”的非敏感快照和最近连接状态，方便设置页展示。
 * command/args 来自后端配置或未来显式保存流程，不保存任何 token 或远程凭据。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_mcp_servers")
public class McpServerEntity {

    /** 数据库内部自增主键；不暴露给桌面端。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** MCP server 稳定标识，例如 local-filesystem。 */
    @TableField("server_id")
    private String serverId;

    /** 用户可读展示名称。 */
    @TableField("display_name")
    private String displayName;

    /** MCP 传输类型；P2-6 仅支持 stdio。 */
    private String transport;

    /** stdio 命令；来自受信任配置，不由 UI 任意输入后直接执行。 */
    private String command;

    /** stdio 参数 JSON 数组。 */
    @TableField("args_json")
    private String argsJson;

    /** stdio 进程工作目录；为空表示继承后端进程目录。 */
    private String cwd;

    /** 是否启用；SQLite 用 0/1 保存，实体用 Boolean 表达业务含义。 */
    private Boolean enabled;

    /** 当前连接状态，disabled、configured、connected 或 failed。 */
    private String status;

    /** 最近一次连接或刷新失败原因；成功时为空。 */
    @TableField("last_error")
    private String lastError;

    /** 创建时间，ISO-8601 字符串。 */
    @TableField("created_at")
    private String createdAt;

    /** 更新时间，ISO-8601 字符串。 */
    @TableField("updated_at")
    private String updatedAt;
}
