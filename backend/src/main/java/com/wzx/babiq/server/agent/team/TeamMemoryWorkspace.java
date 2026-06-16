package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.context.ContextTokenEstimator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 团队记忆工作区。
 *
 * <p>它负责 `team.md`、`digest.md`、`rounds/*.md` 和 `result.md` 的机械落盘，
 * 并把每次写入同步成 `bq_team_artifacts` 记录。成员输出全文只追加保存，
 * 压缩只允许发生在派生的 digest。</p>
 */
@Component
public class TeamMemoryWorkspace {

    /** 文件名安全字符白名单。 */
    private static final Pattern UNSAFE_SLUG = Pattern.compile("[^A-Za-z0-9._-]+");

    /** 团队相关配置。 */
    private final TeamMemoryProperties properties;
    /** token 估算器，用于给摘要和成员上下文预算提供粗略统计。 */
    private final ContextTokenEstimator tokenEstimator;
    /** 团队持久化端口，负责保存产物事实源。 */
    private final TeamRepository repository;

    /**
     * 创建团队记忆工作区。
     */
    public TeamMemoryWorkspace(TeamMemoryProperties properties,
                               ContextTokenEstimator tokenEstimator,
                               TeamRepository repository) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
        this.repository = repository;
    }

    /**
     * 初始化团队目录并写入 `team.md` 头部。
     */
    public void initTeam(String teamId, BabiqTeamSpec spec) {
        String content = """
                # 团队：%s

                目标：%s

                ## 索引
                """.formatted(spec.title(), spec.goal());
        writeArtifact(teamId, "TEAM_INDEX", Path.of("team.md"), content, 0, null);
    }

    /**
     * 写入某轮某成员完整输出。
     */
    public TeamArtifactRecord writeMemberOutput(String teamId, int round, String member, String fullText) {
        String safeMember = safeSlug(member);
        Path relativePath = Path.of("rounds", "r" + Math.max(0, round) + "-" + safeMember + ".md");
        return writeArtifact(teamId, "MEMBER_OUTPUT", relativePath, fullText, round, member);
    }

    /**
     * 追加 `team.md` 索引条目，指向成员全文 Markdown。
     */
    public void appendIndexEntry(String teamId, int round, String member, String oneLine, Path detailRef) {
        Path relativePath = Path.of("team.md");
        Path target = teamDir(teamId).resolve(relativePath);
        String existing = readOrEmpty(target);
        String normalizedRef = normalizeRelativePath(detailRef);
        String entry = "[r%d %s](%s) - %s%n".formatted(
                Math.max(0, round),
                member == null || member.isBlank() ? "unknown" : member,
                normalizedRef,
                oneLine == null ? "" : oneLine);
        writeArtifact(teamId, "TEAM_INDEX", relativePath, ensureTrailingNewline(existing) + entry, 0, null);
    }

    /**
     * 刷新有界讨论概要。
     */
    public TeamArtifactRecord writeDigest(String teamId, String rollingDigestMarkdown) {
        return writeArtifact(teamId, "DIGEST", Path.of("digest.md"), rollingDigestMarkdown, 0, null);
    }

    /**
     * 写入团队最终聚合结果。
     */
    public TeamArtifactRecord writeResult(String teamId, String aggregatedMarkdown) {
        return writeArtifact(teamId, "RESULT", Path.of("result.md"), aggregatedMarkdown, 0, null);
    }

    /**
     * 读取当前滚动讨论概要。
     */
    public String readDigest(String teamId) {
        return readOrEmpty(teamDir(teamId).resolve("digest.md"));
    }

    /**
     * 读取当前团队索引。
     */
    public String readIndex(String teamId) {
        return readOrEmpty(teamDir(teamId).resolve("team.md"));
    }

    /**
     * 返回某团队的磁盘目录。
     */
    public Path teamDir(String teamId) {
        return properties.rootDir().resolve(safeSlug(teamId));
    }

    private TeamArtifactRecord writeArtifact(String teamId,
                                             String artifactType,
                                             Path relativePath,
                                             String content,
                                             int round,
                                             String memberName) {
        try {
            String safeContent = content == null ? "" : content;
            Path teamDir = teamDir(teamId);
            Path target = teamDir.resolve(relativePath);
            Files.createDirectories(target.getParent() == null ? teamDir : target.getParent());
            Files.writeString(target, safeContent, StandardCharsets.UTF_8);
            Instant now = Instant.now();
            TeamArtifactRecord record = new TeamArtifactRecord(
                    new String(teamId == null ? "" : teamId),
                    newArtifactId(),
                    artifactType,
                    normalizeRelativePath(relativePath),
                    sha256(safeContent),
                    tokenEstimator.estimate(safeContent),
                    Math.max(0, round),
                    memberName,
                    safeContent,
                    now,
                    now);
            repository.saveArtifact(record);
            return record;
        } catch (Exception exception) {
            throw new IllegalStateException("团队记忆工作区写入失败: teamId=" + teamId
                    + ", artifactType=" + artifactType, exception);
        }
    }

    private static String readOrEmpty(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (Exception exception) {
            return "";
        }
    }

    private static String ensureTrailingNewline(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("\n") ? value : value + "\n";
    }

    private static String normalizeRelativePath(Path path) {
        return path == null ? "" : path.toString().replace('\\', '/');
    }

    private static String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String safeSlug(String value) {
        String text = value == null ? "" : value.trim();
        String normalized = UNSAFE_SLUG.matcher(text).replaceAll("-");
        normalized = normalized.replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("团队记忆路径标识不能为空");
        }
        return normalized;
    }

    private static String newArtifactId() {
        return "teamart_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
