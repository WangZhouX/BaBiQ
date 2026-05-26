package com.wzx.babiq.server.context;

import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力目录装配测试。
 *
 * <p>P3-1 要把“给模型看的能力摘要”和“Spring AI 实际可调用的 ToolCallback schema”拆开。
 * 这个测试确保目录只暴露名称、来源、说明和风险信息，不把 input schema 塞入上下文 envelope。</p>
 */
class CapabilityCatalogAssemblerTest {

    @Test
    void assemble_should_extract_tool_summaries_without_input_schema() {
        ToolRegistry registry = new ToolRegistry(List.of(new ReadFileTool(), new ExecShellTool()));
        CapabilityCatalogAssembler assembler = new CapabilityCatalogAssembler();

        var catalog = assembler.assemble(registry.allCallbacks());

        assertThat(catalog.toolSummaries()).hasSize(2);
        assertThat(catalog.toolSummaries())
                .extracting("name")
                .containsExactlyInAnyOrder("read_file", "exec_shell");
        assertThat(catalog.toolSummaries())
                .filteredOn(descriptor -> descriptor.name().equals("exec_shell"))
                .singleElement()
                .satisfies(descriptor -> {
                    assertThat(descriptor.source()).isEqualTo("local");
                    assertThat(descriptor.description()).contains("执行命令");
                    assertThat(descriptor.approvalRequired()).isTrue();
                    assertThat(descriptor.riskLevel()).isEqualTo("high");
                });
    }

    private static final class ReadFileTool implements Tool {
        @Override
        public String name() {
            return "read_file";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "read_file", description = "读取文件")
        public String readFile(String path) {
            return path;
        }
    }

    private static final class ExecShellTool implements Tool {
        @Override
        public String name() {
            return "exec_shell";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "exec_shell", description = "执行命令")
        public String execShell(String command) {
            return command;
        }
    }
}
