package com.wzx.babiq.server.context.runtime;

import com.wzx.babiq.server.context.model.ContextAssemblyResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将分层上下文消息渲染成 ReactAgent 当前可消费的临时文本输入。
 *
 * <p>P3-2 先不改 Spring AI Alibaba ReactAgent 的 message 输入形态，而是在调用前生成一段
 * 明确分区的文本。它只作为本轮模型输入，不写回 ThreadItem 历史。</p>
 */
@Component
public class ContextualPromptRenderer {

    /**
     * 渲染上下文装配结果。
     *
     * @param assemblyResult ContextAssembler 输出
     * @return 带有规则、参考上下文和本轮请求三个分区的模型输入文本
     */
    public String render(ContextAssemblyResult assemblyResult) {
        if (assemblyResult == null || assemblyResult.messages().isEmpty()) {
            return "";
        }
        List<Message> messages = assemblyResult.messages();
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof SystemMessage) {
                appendSection(rendered, "Runtime Context Rules", message.getText());
            } else if (i == messages.size() - 1) {
                appendSection(rendered, "Current User Request", message.getText());
            } else {
                appendSection(rendered, "Runtime Context", message.getText());
            }
        }
        return rendered.toString().strip();
    }

    private static void appendSection(StringBuilder rendered, String title, String text) {
        if (rendered.length() > 0) {
            rendered.append("\n\n");
        }
        rendered.append("## ").append(title).append("\n");
        rendered.append(text == null ? "" : text.strip());
    }
}
