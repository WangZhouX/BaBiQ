package com.wzx.babiq.server.mcp;

import java.util.List;

/**
 * 单个 MCP server 的受信任配置。
 *
 * <p>P2-6 只支持本地 stdio server，并且配置来自后端配置文件或持久化层，
 * 不允许桌面端随意输入命令后立即执行。这个 record 只表达“可以被启动的配置快照”，
 * 真正是否启动由 {@link McpProperties#enabledServers()} 统一判断。</p>
 *
 * @param id server 稳定标识，会进入工具命名空间，例如 local-filesystem
 * @param displayName UI 展示名称，为空时回退到 id
 * @param transport 传输类型，P2-6 只支持 stdio
 * @param command stdio 进程命令，例如 node、npx 或本地可执行文件路径
 * @param args stdio 命令参数；由后端受信任配置提供，不能来自未确认的 UI 输入
 * @param cwd stdio 进程工作目录；为空时继承后端进程工作目录
 * @param enabled 当前 server 是否参与启动连接
 * @param approvalPolicy 预留审批策略字段；P2-6 统一走 BaBiQ HITL ON_REQUEST 链路
 */
public record McpServerConfig(
        String id,
        String displayName,
        String transport,
        String command,
        List<String> args,
        String cwd,
        boolean enabled,
        String approvalPolicy
) {

    /**
     * 补齐默认值并冻结参数列表。
     */
    public McpServerConfig {
        if (id != null) {
            id = id.trim();
        }
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        transport = transport == null || transport.isBlank() ? "stdio" : transport.trim();
        command = command == null ? "" : command.trim();
        args = args == null ? List.of() : List.copyOf(args);
        cwd = cwd == null || cwd.isBlank() ? null : cwd.trim();
        approvalPolicy = approvalPolicy == null || approvalPolicy.isBlank() ? "ON_REQUEST" : approvalPolicy.trim();
    }

    /**
     * 启动前校验 server 配置。
     *
     * <p>这里是 P2-6 的第一道安全边界：BaBiQ 只接受本地 stdio，且启用的 server 必须有明确 command。
     * 如果未来做远程 MCP 或 OAuth，需要新增传输类型和更细的密钥/权限模型。</p>
     */
    public void validateForStartup() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("MCP server id 不能为空");
        }
        if (!"stdio".equalsIgnoreCase(transport)) {
            throw new IllegalArgumentException("P2-6 仅支持 stdio MCP transport: " + transport);
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("启用的 stdio MCP server 必须配置 command: " + id);
        }
    }
}
