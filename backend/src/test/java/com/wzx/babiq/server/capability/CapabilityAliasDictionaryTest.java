package com.wzx.babiq.server.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力中文别名字典测试。
 *
 * <p>这些用例固定 BaBiQ 的中文 query 补偿策略：工具名仍保持 ASCII，
 * 但写入 Lucene 的 searchText 必须包含典型中文词面。</p>
 */
class CapabilityAliasDictionaryTest {

    @Test
    @DisplayName("read_file 会追加读取和查看等中文别名")
    void enrich_should_add_read_aliases() {
        String enriched = CapabilityAliasDictionary.enrich("read_file", "Read a file");

        assertThat(enriched).contains("Read a file", "读取", "查看", "打开", "文件内容");
    }

    @Test
    @DisplayName("MCP 文件系统工具会同时命中文件系统和读取别名")
    void enrich_should_add_aliases_for_mcp_filesystem_tool() {
        String enriched = CapabilityAliasDictionary.enrich("mcp.filesystem.read_text_file", "Read text");

        assertThat(enriched).contains("文件系统", "文件", "读取", "查看");
    }

    @Test
    @DisplayName("exec_shell 会同时追加执行和终端别名")
    void enrich_should_add_aliases_for_exec_shell() {
        String enriched = CapabilityAliasDictionary.enrich("exec_shell", "Execute shell command");

        assertThat(enriched).contains("执行", "运行", "命令", "终端", "命令行", "bash");
    }

    @Test
    @DisplayName("未知 token 不会破坏原始 searchText")
    void enrich_should_keep_original_text_for_unknown_tokens() {
        String enriched = CapabilityAliasDictionary.enrich("noop", "No operation");

        assertThat(enriched).isEqualTo("No operation");
    }

    @Test
    @DisplayName("update_plan 会追加计划和待办等中文别名")
    void enrich_should_add_aliases_for_update_plan() {
        String enriched = CapabilityAliasDictionary.enrich("update_plan", "Updates the task plan");

        assertThat(enriched).contains("计划", "任务清单", "待办", "步骤", "规划");
    }

    @Test
    @DisplayName("explorer 能力会追加子 Agent 委派相关中文别名")
    void enrich_should_add_aliases_for_explorer_delegation() {
        String enriched = CapabilityAliasDictionary.enrich("explorer", "Delegate read-only exploration to a sub agent");

        assertThat(enriched)
                .contains("子Agent")
                .contains("委派")
                .contains("探索")
                .contains("只读");
    }
}
