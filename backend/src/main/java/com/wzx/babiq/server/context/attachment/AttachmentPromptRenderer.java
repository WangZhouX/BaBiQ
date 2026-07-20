package com.wzx.babiq.server.context.attachment;

import com.wzx.babiq.server.attachment.AttachmentTextSegment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Renders one ephemeral attachment text segment as an explicitly delimited,
 * untrusted data block for the current model invocation.
 */
@Component
public class AttachmentPromptRenderer {

    static final String TRUNCATION_MARKER = "[附件内容已截断]";

    /**
     * Render a segment without exposing any local path or content hash.
     *
     * <p>Both labels and body are XML-escaped. Escaping the body is intentional:
     * extracted text cannot inject a closing {@code attachment} tag and escape
     * the untrusted-data boundary.</p>
     */
    public String render(AttachmentTextSegment segment, String includedBody, boolean truncated) {
        Objects.requireNonNull(segment, "segment");
        String body = includedBody == null ? "" : includedBody;
        StringBuilder rendered = new StringBuilder()
                .append("<attachment id=\"")
                .append(escapeXml(segment.displayId(), true))
                .append("\" name=\"")
                .append(escapeXml(segment.name(), true))
                .append("\" content_type=\"")
                .append(escapeXml(segment.mediaType(), true))
                .append("\">\n")
                .append(escapeXml(body, false));
        if (truncated) {
            if (!body.isEmpty()) {
                rendered.append('\n');
            }
            rendered.append(TRUNCATION_MARKER);
        }
        return rendered.append("\n</attachment>").toString();
    }

    private static String escapeXml(String value, boolean attribute) {
        String safe = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(safe.length());
        safe.codePoints().forEach(codePoint -> {
            if (!isValidXmlCodePoint(codePoint)) {
                escaped.append('\uFFFD');
                return;
            }
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append(attribute ? "&quot;" : "\"");
                case '\'' -> escaped.append(attribute ? "&apos;" : "'");
                default -> escaped.appendCodePoint(codePoint);
            }
        });
        return escaped.toString();
    }

    private static boolean isValidXmlCodePoint(int codePoint) {
        return codePoint == 0x9
                || codePoint == 0xA
                || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
