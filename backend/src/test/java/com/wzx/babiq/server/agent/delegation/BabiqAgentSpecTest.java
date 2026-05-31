package com.wzx.babiq.server.agent.delegation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置子 Agent 规格测试。
 *
 * <p>P6-1 只允许先落地只读 explorer。这里把工具白名单和委派模式钉住，
 * 避免后续实现把写文件、执行命令或尚未落地的 glob 混进 explorer。</p>
 */
class BabiqAgentSpecTest {

    @Test
    void explorer_spec_should_only_expose_existing_read_only_tools() {
        BabiqAgentSpec explorer = BuiltInSubAgents.explorer();

        assertThat(explorer.name()).isEqualTo("explorer");
        assertThat(explorer.displayName()).isEqualTo("探索子 Agent");
        assertThat(explorer.delegationMode()).isEqualTo(BabiqAgentMode.READ_ONLY_TOOL);
        assertThat(explorer.toolNames()).containsExactly("read_file", "list_dir", "grep");
        assertThat(explorer.toolNames())
                .doesNotContain("write_file", "exec_shell", "apply_patch", "glob");
        assertThat(explorer.modelPolicy()).isEqualTo(BabiqAgentSpec.ModelPolicy.inherit());
        assertThat(explorer.systemPrompt())
                .contains("READ-ONLY")
                .contains("untrusted-data");
    }
}
