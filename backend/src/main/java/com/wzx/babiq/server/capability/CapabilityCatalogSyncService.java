package com.wzx.babiq.server.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.mcp.McpToolCatalog;
import com.wzx.babiq.server.mcp.McpToolDescriptor;
import com.wzx.babiq.server.skill.LocalSkillRegistry;
import com.wzx.babiq.server.skill.SkillDescriptor;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 能力目录同步服务。
 *
 * <p>它把本地 Tool、MCP Tool 和 Skill metadata 汇总到 `bq_capabilities`。
 * 同步只保存摘要和 hash，不保存完整工具 schema 或 Skill 正文，避免上下文和数据库里混入大段敏感输入。</p>
 */
@Service
public class CapabilityCatalogSyncService {

    /** 本地工具注册表。 */
    private final ToolRegistry toolRegistry;
    /** MCP 目录可选依赖；未启用 MCP 时自动为空。 */
    private final ObjectProvider<McpToolCatalog> mcpToolCatalogProvider;
    /** Skill 注册表可选依赖；配置目录为空时返回空列表。 */
    private final ObjectProvider<LocalSkillRegistry> skillRegistryProvider;
    /** 能力目录仓库。 */
    private final CapabilityRepository repository;
    /** JSON mapper，用于稳定生成 schema hash。 */
    private final ObjectMapper objectMapper;
    /** 能力目录变化事件发布器；Lucene 索引等派生组件通过事件重建，不反向耦合同步流程。 */
    private final ApplicationEventPublisher events;

    /**
     * 创建能力同步服务。
     */
    public CapabilityCatalogSyncService(ToolRegistry toolRegistry,
                                        ObjectProvider<McpToolCatalog> mcpToolCatalogProvider,
                                        ObjectProvider<LocalSkillRegistry> skillRegistryProvider,
                                        CapabilityRepository repository,
                                        ObjectMapper objectMapper,
                                        ApplicationEventPublisher events) {
        this.toolRegistry = toolRegistry;
        this.mcpToolCatalogProvider = mcpToolCatalogProvider;
        this.skillRegistryProvider = skillRegistryProvider;
        this.repository = repository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.events = events;
    }

    /**
     * 扫描当前进程内所有已知能力并同步到 SQLite。
     */
    public void sync() {
        Instant now = Instant.now();
        repository.upsertAll(localToolCapabilities(now));
        repository.upsertAll(mcpToolCapabilities(now));
        repository.upsertAll(skillCapabilities(now));
        if (events != null) {
            events.publishEvent(new CapabilityCatalogChangedEvent(this));
        }
    }

    private List<CapabilityDescriptor> localToolCapabilities(Instant now) {
        return java.util.Arrays.stream(toolRegistry.localCallbacks())
                .map(callback -> {
                    String name = callback.getToolDefinition().name();
                    String description = safeDescription(callback);
                    return new CapabilityDescriptor(
                            "local." + name,
                            CapabilityType.LOCAL_TOOL,
                            "local",
                            name,
                            name,
                            description,
                            "local",
                            sha256(name + description),
                            searchText(name, description, "local tool"),
                            CapabilityExposureMode.VISIBLE,
                            true,
                            now);
                })
                .toList();
    }

    private List<CapabilityDescriptor> mcpToolCapabilities(Instant now) {
        McpToolCatalog catalog = mcpToolCatalogProvider == null ? null : mcpToolCatalogProvider.getIfAvailable();
        if (catalog == null) {
            return List.of();
        }
        return catalog.descriptors().stream()
                .map(descriptor -> new CapabilityDescriptor(
                        "mcp." + descriptor.serverId() + "." + descriptor.toolName(),
                        CapabilityType.MCP_TOOL,
                        descriptor.serverId(),
                        descriptor.namespacedName(),
                        descriptor.toolName(),
                        descriptor.description(),
                        descriptor.serverId(),
                        sha256(descriptor.inputSchema()),
                        searchText(descriptor.namespacedName(), descriptor.description(), descriptor.serverId()),
                        CapabilityExposureMode.DEFERRED,
                        descriptor.enabled(),
                        now))
                .toList();
    }

    private List<CapabilityDescriptor> skillCapabilities(Instant now) {
        LocalSkillRegistry registry = skillRegistryProvider == null ? null : skillRegistryProvider.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        return registry.listSkills().stream()
                .map(skill -> toCapability(skill, now))
                .toList();
    }

    private CapabilityDescriptor toCapability(SkillDescriptor skill, Instant now) {
        return new CapabilityDescriptor(
                "skill." + skill.id(),
                CapabilityType.SKILL,
                skill.namespace(),
                skill.name(),
                skill.name(),
                skill.description(),
                skill.sourceDirectory(),
                skill.contentHash(),
                searchText(skill.name(), skill.description(), skill.namespace()),
                CapabilityExposureMode.DEFERRED,
                true,
                now);
    }

    private String safeDescription(ToolCallback callback) {
        String description = callback.getToolDefinition().description();
        return description == null || description.isBlank()
                ? callback.getToolDefinition().name()
                : description;
    }

    private String searchText(String name, String description, String tags) {
        String originalSearchText = (safe(name) + " " + safe(description) + " " + safe(tags)).trim();
        // 中文别名只能进入辅助检索文本，不能反向改工具 name，否则会破坏 function calling 协议。
        return CapabilityAliasDictionary.enrich(name, originalSearchText);
    }

    private String sha256(Object value) {
        try {
            String text = value instanceof String s ? s : objectMapper.writeValueAsString(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
