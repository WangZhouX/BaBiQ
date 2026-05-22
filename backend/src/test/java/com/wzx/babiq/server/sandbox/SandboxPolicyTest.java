package com.wzx.babiq.server.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SandboxPolicy 仅承载三种模式和白名单根目录，不做路径判断。
 */
class SandboxPolicyTest {

    @Test
    void exposes_read_only_flag() {
        SandboxPolicy policy = new SandboxPolicy(SandboxMode.READ_ONLY, List.of(Path.of(".")));
        assertThat(policy.isReadOnly()).isTrue();
        assertThat(policy.isFullAccess()).isFalse();
    }

    @Test
    void exposes_full_access_flag() {
        SandboxPolicy policy = new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, List.of());
        assertThat(policy.isFullAccess()).isTrue();
        assertThat(policy.isReadOnly()).isFalse();
    }
}
