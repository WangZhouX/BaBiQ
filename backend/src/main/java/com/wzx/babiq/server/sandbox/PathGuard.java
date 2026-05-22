package com.wzx.babiq.server.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径守卫。
 *
 * <p>核心规则：永远比较真实路径，不能用字符串前缀。这样才能挡住 .. 穿越和符号链接逃逸。</p>
 */
public final class PathGuard {

    private final List<Path> writableRoots;

    /**
     * 构造路径守卫。
     *
     * @param writableRoots 白名单根目录
     */
    public PathGuard(List<Path> writableRoots) {
        List<Path> normalizedRoots = new ArrayList<>();
        for (Path writableRoot : writableRoots == null ? List.<Path>of() : writableRoots) {
            normalizedRoots.add(normalizeRoot(writableRoot));
        }
        this.writableRoots = List.copyOf(normalizedRoots);
    }

    /**
     * 校验读取路径。
     *
     * @param rawPath 原始路径字符串
     * @return 真实可访问路径
     */
    public Path checkRead(String rawPath) {
        Path resolved = resolve(rawPath);
        if (!isUnderAnyRoot(resolved)) {
            throw new SandboxViolationException("Path outside writable roots: " + rawPath);
        }
        return resolved;
    }

    /**
     * 校验写入路径。
     *
     * @param rawPath 原始路径字符串
     * @return 真实可访问路径
     */
    public Path checkWrite(String rawPath) {
        return checkRead(rawPath);
    }

    private Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new SandboxViolationException("Path is blank");
        }

        Path absolute = Paths.get(rawPath).toAbsolutePath().normalize();
        Path probe = absolute;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return absolute;
        }

        try {
            Path realProbe = probe.toRealPath();
            if (probe.equals(absolute)) {
                return realProbe;
            }
            Path remaining = probe.relativize(absolute);
            return realProbe.resolve(remaining).normalize();
        } catch (IOException exception) {
            throw new SandboxViolationException("Unable to resolve path: " + rawPath + ", reason=" + exception.getMessage());
        }
    }

    private boolean isUnderAnyRoot(Path candidate) {
        if (writableRoots.isEmpty()) {
            throw new SandboxViolationException("No writable roots configured");
        }
        for (Path writableRoot : writableRoots) {
            if (candidate.startsWith(writableRoot)) {
                return true;
            }
        }
        return false;
    }

    private Path normalizeRoot(Path root) {
        if (root == null) {
            throw new SandboxViolationException("Writable root is blank");
        }
        try {
            return Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    ? root.toRealPath()
                    : root.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new SandboxViolationException("Unable to normalize writable root: " + root + ", reason=" + exception.getMessage());
        }
    }
}
