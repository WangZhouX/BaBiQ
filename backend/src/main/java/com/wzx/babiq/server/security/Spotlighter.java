package com.wzx.babiq.server.security;

import org.springframework.stereotype.Component;

/**
 * 将外部工具结果标注为不可信数据。
 *
 * <p>LLM 仍然需要读取工具输出,但输出中的文本不能被当成新指令执行。这里采用
 * Anthropic spotlighting 同类思路:工具结果进入模型上下文前统一包在
 * {@code <untrusted-data>} 标签中,并转义正文里的标签边界,避免工具内容伪造闭合标签。</p>
 */
@Component
public class Spotlighter {

    /**
     * 包装工具结果。
     *
     * @param toolName 工具名称
     * @param path 工具目标路径,没有路径时可为 null
     * @param result 原始工具结果
     * @return 带不可信数据边界的安全文本
     */
    public String wrapToolResult(String toolName, String path, String result) {
        StringBuilder wrapped = new StringBuilder();
        wrapped.append("<untrusted-data source=\"tool:")
                .append(escapeAttribute(toolName))
                .append("\"");
        if (path != null && !path.isBlank()) {
            wrapped.append(" path=\"").append(escapeAttribute(path)).append("\"");
        }
        wrapped.append(">");
        wrapped.append(escapeBody(result == null ? "" : result));
        wrapped.append("</untrusted-data>");
        return wrapped.toString();
    }

    private String escapeBody(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeAttribute(String text) {
        return escapeBody(text == null ? "" : text)
                .replace("\"", "&quot;");
    }
}
