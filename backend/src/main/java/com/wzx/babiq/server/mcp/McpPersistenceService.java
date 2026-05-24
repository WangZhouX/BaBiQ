package com.wzx.babiq.server.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.persistence.entity.McpServerEntity;
import com.wzx.babiq.server.persistence.entity.McpToolEntity;
import com.wzx.babiq.server.persistence.mapper.McpServerMapper;
import com.wzx.babiq.server.persistence.mapper.McpToolMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * MCP 配置和工具目录持久化服务。
 *
 * <p>它只负责 SQLite 读写，不启动外部进程。McpClientManager 在启动和刷新时调用它，
 * 设置页读取状态时也可以从 manager 看到这一层同步后的结果。</p>
 */
@Service
public class McpPersistenceService {

    /** 用于序列化 stdio args JSON 数组。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 反序列化 args_json 的类型引用。 */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /** MCP server 配置 mapper。 */
    private final McpServerMapper serverMapper;
    /** MCP 工具目录 mapper。 */
    private final McpToolMapper toolMapper;

    /**
     * 创建 MCP 持久化服务。
     *
     * @param serverMapper server 表 mapper
     * @param toolMapper tool 表 mapper
     */
    public McpPersistenceService(McpServerMapper serverMapper, McpToolMapper toolMapper) {
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
    }

    /**
     * 把 YAML 中的受信任 server 配置同步到 SQLite。
     */
    @Transactional
    public void bootstrapYamlServers(List<McpServerConfig> servers) {
        Instant now = Instant.now();
        for (McpServerConfig config : servers) {
            McpServerEntity existing = serverMapper.selectOne(Wrappers.<McpServerEntity>lambdaQuery()
                    .eq(McpServerEntity::getServerId, config.id()));
            McpServerEntity entity = toEntity(config, existing, now);
            if (existing == null) {
                serverMapper.insert(entity);
            } else {
                entity.setId(existing.getId());
                serverMapper.updateById(entity);
            }
        }
    }

    /**
     * 更新 server 运行状态。
     */
    @Transactional
    public void markServerStatus(String serverId, String status, String lastError, Instant now) {
        McpServerEntity existing = serverMapper.selectOne(Wrappers.<McpServerEntity>lambdaQuery()
                .eq(McpServerEntity::getServerId, serverId));
        if (existing == null) {
            return;
        }
        existing.setStatus(status);
        existing.setLastError(lastError);
        existing.setUpdatedAt(writeTime(now));
        serverMapper.updateById(existing);
    }

    /**
     * 用最新 listTools 结果替换某个 server 的工具目录。
     */
    @Transactional
    public void replaceTools(String serverId, List<McpToolDescriptor> tools, Instant now) {
        toolMapper.delete(Wrappers.<McpToolEntity>lambdaQuery().eq(McpToolEntity::getServerId, serverId));
        for (McpToolDescriptor tool : tools) {
            toolMapper.insert(toEntity(tool, now));
        }
    }

    /**
     * 读取某个 server 最近持久化的工具目录。
     */
    public List<McpToolDescriptor> listTools(String serverId) {
        return toolMapper.selectList(Wrappers.<McpToolEntity>lambdaQuery()
                        .eq(McpToolEntity::getServerId, serverId)
                        .orderByAsc(McpToolEntity::getToolName))
                .stream()
                .map(this::toDescriptor)
                .toList();
    }

    private McpServerEntity toEntity(McpServerConfig config, McpServerEntity existing, Instant now) {
        McpServerEntity entity = new McpServerEntity();
        entity.setServerId(config.id());
        entity.setDisplayName(config.displayName());
        entity.setTransport(config.transport());
        entity.setCommand(config.command());
        entity.setArgsJson(writeArgs(config.args()));
        entity.setCwd(config.cwd());
        entity.setEnabled(config.enabled());
        entity.setStatus(existing == null ? (config.enabled() ? "configured" : "disabled") : existing.getStatus());
        entity.setLastError(existing == null ? null : existing.getLastError());
        entity.setCreatedAt(existing == null ? writeTime(now) : existing.getCreatedAt());
        entity.setUpdatedAt(writeTime(now));
        return entity;
    }

    private McpToolEntity toEntity(McpToolDescriptor descriptor, Instant now) {
        McpToolEntity entity = new McpToolEntity();
        entity.setServerId(descriptor.serverId());
        entity.setToolName(descriptor.toolName());
        entity.setNamespacedName(descriptor.namespacedName());
        entity.setDescription(descriptor.description());
        entity.setSchemaJson(descriptor.inputSchema());
        entity.setEnabled(descriptor.enabled());
        entity.setUpdatedAt(writeTime(now));
        return entity;
    }

    private McpToolDescriptor toDescriptor(McpToolEntity entity) {
        return new McpToolDescriptor(
                entity.getServerId(),
                entity.getToolName(),
                entity.getNamespacedName(),
                entity.getDescription(),
                entity.getSchemaJson(),
                Boolean.TRUE.equals(entity.getEnabled()));
    }

    private String writeArgs(List<String> args) {
        try {
            return OBJECT_MAPPER.writeValueAsString(args == null ? List.of() : args);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP args 序列化失败", exception);
        }
    }

    /**
     * MCP 表保存 ISO-8601 文本时间，和现有 P2 运行记录表保持同一种可读格式。
     */
    private String writeTime(Instant instant) {
        return (instant == null ? Instant.now() : instant).toString();
    }

    @SuppressWarnings("unused")
    private List<String> readArgs(String argsJson) {
        try {
            return argsJson == null || argsJson.isBlank() ? List.of() : OBJECT_MAPPER.readValue(argsJson, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }
}
