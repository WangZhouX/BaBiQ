package com.wzx.babiq.server.sandbox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PathGuard 的核心回归测试。
 *
 * <p>这里专门覆盖真实攻击面：目录穿越、符号链接逃逸、非存在子路径和空白名单。
 * 所有路径判断都必须走真实路径比较，不能用字符串前缀。</p>
 */
class PathGuardTest {

    @Test
    void allows_existing_file_inside_root(@TempDir Path root) throws Exception {
        Path file = root.resolve("note.txt");
        Files.writeString(file, "hello");
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));

        assertThat(guard.checkRead(file.toString())).isEqualTo(file.toRealPath());
        assertThat(guard.checkWrite(file.toString())).isEqualTo(file.toRealPath());
    }

    @Test
    void rejects_path_traversal_outside_root(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));

        assertThatThrownBy(() -> guard.checkRead(root.resolve("..").resolve("escape.txt").toString()))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void rejects_symbolic_link_escape(@TempDir Path root) throws Exception {
        Path outside = Files.createTempDirectory("babiq-outside-");
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.assumeTrue(false, "当前环境不支持创建符号链接，跳过此用例");
            return;
        }

        PathGuard guard = new PathGuard(List.of(root.toRealPath()));
        assertThatThrownBy(() -> guard.checkRead(link.resolve("escape.txt").toString()))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void allows_nonexistent_child_for_write(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));

        Path resolved = guard.checkWrite(root.resolve("subdir/new.txt").toString());
        assertThat(resolved.normalize().startsWith(root.toRealPath())).isTrue();
    }

    @Test
    void rejects_when_root_list_is_empty() {
        PathGuard guard = new PathGuard(List.of());

        assertThatThrownBy(() -> guard.checkRead("C:/anywhere"))
                .isInstanceOf(SandboxViolationException.class);
    }
}
