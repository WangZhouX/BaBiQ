package com.wzx.babiq.server.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.context.ContextAssembler;
import com.wzx.babiq.server.context.model.CapabilityCatalog;
import com.wzx.babiq.server.context.model.ContextAssemblyInput;
import com.wzx.babiq.server.context.model.ContextAssemblyResult;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-2 模型输入渲染测试。
 *
 * <p>ContextAssembler 负责结构化装配，ContextualPromptRenderer 负责把结构化消息转成
 * ReactAgent 当前可消费的一段文本。这个测试保证渲染结果仍然保留分层边界，并把本轮用户输入放在最后。</p>
 */
class ContextualPromptRendererTest {

    @Test
    @DisplayName("渲染后的模型输入保留规则、历史和本轮请求的分层顺序")
    void render_should_keep_layered_context_and_current_turn_order() {
        ContextAssembler assembler = new ContextAssembler(new ObjectMapper(), text -> text == null ? 0 : 1);
        ContextAssemblyResult assembled = assembler.assemble(new ContextAssemblyInput(
                "thr_context",
                "turn_current",
                "请总结当前项目",
                "E:\\BaBiQ",
                "BaBiQ",
                "WORKSPACE_WRITE",
                "ON_REQUEST",
                List.of(
                        UserMessageItem.of("it_old_user", "旧问题"),
                        AgentMessageItem.full("it_old_agent", "旧回答")),
                null,
                List.of(),
                List.of("当前工作目录: E:\\BaBiQ"),
                new CapabilityCatalog(List.of())));

        String rendered = new ContextualPromptRenderer().render(assembled);

        assertThat(rendered)
                .contains("## Runtime Context Rules")
                .contains("## Runtime Context")
                .contains("## Current User Request")
                .contains("旧问题")
                .endsWith("请总结当前项目");
    }
}
