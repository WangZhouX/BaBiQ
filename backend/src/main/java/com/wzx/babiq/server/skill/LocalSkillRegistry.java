package com.wzx.babiq.server.skill;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 本地 Skill metadata 注册表。
 *
 * <p>它只读取配置目录下的 `SKILL.md` front matter 和少量文本摘要，不会执行 Skill 文件中的脚本。
 * 真正加载正文必须通过 SkillContentLoader 或 JSON-RPC `skills/get` 显式请求。</p>
 */
@Service
public class LocalSkillRegistry {

    /** Skill 扫描配置。 */
    private final SkillProperties properties;

    /**
     * 创建本地 Skill 注册表。
     */
    public LocalSkillRegistry(SkillProperties properties) {
        this.properties = properties;
    }

    /**
     * 列出所有可见 Skill metadata。
     */
    public List<SkillDescriptor> listSkills() {
        if (!properties.enabled()) {
            return List.of();
        }
        List<SkillDescriptor> descriptors = new ArrayList<>();
        for (Path directory : properties.directories()) {
            descriptors.addAll(scanDirectory(directory));
        }
        return descriptors.stream()
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

    private List<SkillDescriptor> scanDirectory(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root, 3)) {
            return stream.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .map(path -> readDescriptor(root, path))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private Optional<SkillDescriptor> readDescriptor(Path root, Path skillFile) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String name = frontMatter(content, "name").orElse(skillFile.getParent().getFileName().toString());
            String description = frontMatter(content, "description").orElse(name);
            String namespace = root.relativize(skillFile.getParent()).getNameCount() > 1
                    ? root.relativize(skillFile.getParent()).getName(0).toString()
                    : "local";
            String id = (namespace + "." + name).replaceAll("[^A-Za-z0-9_.-]", "_");
            return Optional.of(new SkillDescriptor(id, namespace, name, description,
                    root.toAbsolutePath().toString(), skillFile.toAbsolutePath().toString(), sha256(content)));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> frontMatter(String content, String key) {
        if (content == null || !content.startsWith("---")) {
            return Optional.empty();
        }
        String[] lines = content.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                break;
            }
            String prefix = key + ":";
            if (lines[i].startsWith(prefix)) {
                return Optional.of(lines[i].substring(prefix.length()).trim());
            }
        }
        return Optional.empty();
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
