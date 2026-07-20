package com.wzx.babiq.server.attachment;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Locale;
import java.util.Optional;

/**
 * 把 Provider 明确返回的图片/媒体能力拒绝归一为稳定附件错误。
 *
 * <p>分类只读取异常链用于内存内判断，绝不把远端响应正文或底层异常文本放进返回消息。</p>
 */
public final class AttachmentModelFailureClassifier {

    private static final String SAFE_MESSAGE =
            "当前 Provider/模型不支持图片附件或中转站未开启多模态转发";

    /**
     * 在本次确实发送过图片时识别明确的多模态能力拒绝。
     *
     * @param failure 模型调用异常
     * @param imageInputPresent 本次模型请求是否携带图片媒体
     * @return 已识别时返回稳定附件异常，否则留给通用失败分类处理
     */
    public Optional<AttachmentException> classify(
            Throwable failure,
            boolean imageInputPresent
    ) {
        if (!imageInputPresent || failure == null) {
            return Optional.empty();
        }
        WebClientResponseException response =
                findCause(failure, WebClientResponseException.class);
        if (response == null) {
            return Optional.empty();
        }
        int status = response.getStatusCode().value();
        String diagnostic = (
                safe(response.getMessage()) + " " + safe(response.getResponseBodyAsString()))
                .toLowerCase(Locale.ROOT);
        boolean knownRejection = status == 415
                || (status == 400 || status == 422)
                && containsAny(diagnostic,
                        "image", "image_url", "vision", "multimodal", "media",
                        "图片", "多模态")
                && containsAny(diagnostic,
                        "not support", "unsupported", "does not support", "not enabled",
                        "invalid content", "must be text", "text only",
                        "不支持", "未开启", "仅支持文本");
        if (!knownRejection) {
            return Optional.empty();
        }
        return Optional.of(new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_MODEL_UNSUPPORTED,
                SAFE_MESSAGE));
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
