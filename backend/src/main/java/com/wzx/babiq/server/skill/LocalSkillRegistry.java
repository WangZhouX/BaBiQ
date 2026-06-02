package com.wzx.babiq.server.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地 Skill metadata 注册表。
 *
 * <p>P3-6 开始，Skill front matter 解析交给 Spring AI Alibaba 官方
 * {@link FileSystemSkillRegistry}。本类只负责把官方 metadata 适配成 BaBiQ
 * 稳定协议：保留 namespace/id、按当前 cwd 扫描项目 Skill、按需读取正文。</p>
 */
@Service
public class LocalSkillRegistry {

    private static final String LOCAL_NAMESPACE = "local";
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String EMPTY_PROJECT_SLOT = ".babiq-empty-project-skills";

    /** Skill 扫描配置。 */
    private final SkillProperties properties;
    /** 当前项目 Skill 目录提供者。 */
    private final SkillProjectDirectoryProvider projectDirectoryProvider;

    /**
     * Spring 使用的构造器，项目目录会跟随设置里的当前工作区变化。
     */
    @Autowired
    public LocalSkillRegistry(SkillProperties properties, SkillProjectDirectoryProvider projectDirectoryProvider) {
        this.properties = properties;
        this.projectDirectoryProvider = projectDirectoryProvider;
    }

    /**
     * 测试和兼容旧调用点使用的构造器。
     */
    public LocalSkillRegistry(SkillProperties properties) {
        this(properties, properties::projectSkillsDirectory);
    }

    /**
     * 列出所有可见 Skill metadata。
     */
    public List<SkillDescriptor> listSkills() {
        if (!properties.enabled()) {
            return List.of();
        }
        Map<String, SkillDescriptor> byId = new LinkedHashMap<>();
        for (Path root : configuredRoots()) {
            for (RegistrySlot slot : slotsForRoot(root)) {
                for (SkillMetadata metadata : metadataFrom(slot)) {
                    toDescriptor(slot, metadata).ifPresent(descriptor -> byId.put(descriptor.id(), descriptor));
                }
            }
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(SkillDescriptor::id))
                .toList();
    }

    /**
     * 按 id 查找 Skill。
     */
    public Optional<SkillDescriptor> findById(String id) {
        return listSkills().stream()
                .filter(skill -> skill.id().equals(id))
                .findFirst();
    }

    /**
     * 通过官方 registry 按需读取 Skill 正文。
     */
    public String readFullContent(SkillDescriptor descriptor) throws IOException {
        for (Path root : configuredRoots()) {
            for (RegistrySlot slot : slotsForRoot(root)) {
                slot.registry().reload();
                for (SkillMetadata metadata : slot.registry().listAll()) {
                    Path skillFile = skillFile(metadata);
                    if (skillFile.toString().equals(descriptor.skillFile())) {
                        return slot.registry().readSkillContent(metadata.getName());
                    }
                }
            }
        }
        throw new IOException("Skill registry entry not found: " + descriptor.id());
    }

    private List<Path> configuredRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(properties.userSkillsDirectory());
        roots.addAll(properties.additionalDirectories());
        roots.addAll(properties.directories());
        roots.add(currentProjectDirectory());
        return roots.stream()
                .filter(root -> root != null)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private Path currentProjectDirectory() {
        Path provided = projectDirectoryProvider == null ? null : projectDirectoryProvider.projectSkillsDirectory();
        return provided == null ? properties.projectSkillsDirectory() : provided;
    }

    private List<RegistrySlot> slotsForRoot(Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        List<RegistrySlot> slots = new ArrayList<>();
        slots.add(new RegistrySlot(normalizedRoot, normalizedRoot, LOCAL_NAMESPACE, officialRegistry(normalizedRoot)));
        try (Stream<Path> children = Files.list(normalizedRoot)) {
            children.filter(Files::isDirectory)
                    .sorted()
                    .forEach(child -> slots.add(new RegistrySlot(
                            normalizedRoot,
                            child.toAbsolutePath().normalize(),
                            child.getFileName().toString(),
                            officialRegistry(child))));
        } catch (IOException ignored) {
            return slots;
        }
        return slots;
    }

    private SkillRegistry officialRegistry(Path scanRoot) {
        Path normalized = scanRoot.toAbsolutePath().normalize();
        return FileSystemSkillRegistry.builder()
                .userSkillsDirectory(normalized.toString())
                .projectSkillsDirectory(normalized.resolve(EMPTY_PROJECT_SLOT).toString())
                .autoLoad(false)
                .build();
    }

    private List<SkillMetadata> metadataFrom(RegistrySlot slot) {
        try {
            slot.registry().reload();
            return slot.registry().listAll();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private Optional<SkillDescriptor> toDescriptor(RegistrySlot slot, SkillMetadata metadata) {
        Path skillFile = skillFile(metadata);
        if (!Files.isRegularFile(skillFile)) {
            return Optional.empty();
        }
        try {
            String rawContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            String namespace = namespaceFor(slot, skillFile);
            String name = metadata.getName();
            String id = (namespace + "." + name).replaceAll("[^A-Za-z0-9_.-]", "_");
            return Optional.of(new SkillDescriptor(
                    id,
                    namespace,
                    name,
                    metadata.getDescription(),
                    slot.sourceRoot().toString(),
                    skillFile.toString(),
                    sha256(rawContent),
                    metadata.getAllowedTools()));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String namespaceFor(RegistrySlot slot, Path skillFile) {
        Path parent = skillFile.getParent();
        Path relative = slot.sourceRoot().relativize(parent);
        if (relative.getNameCount() > 1) {
            return relative.getName(0).toString();
        }
        return slot.namespace();
    }

    private Path skillFile(SkillMetadata metadata) {
        Path path = Path.of(metadata.getSkillPath()).toAbsolutePath().normalize();
        if (path.getFileName() != null && SKILL_FILE_NAME.equals(path.getFileName().toString())) {
            return path;
        }
        return path.resolve(SKILL_FILE_NAME).toAbsolutePath().normalize();
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(content.hashCode());
        }
    }

    private record RegistrySlot(Path sourceRoot, Path scanRoot, String namespace, SkillRegistry registry) {
    }
}
