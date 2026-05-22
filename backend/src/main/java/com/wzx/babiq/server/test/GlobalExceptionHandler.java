package com.wzx.babiq.server.test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * P1-2 临时 REST 端点的全局异常处理。
 *
 * <p>目标是把 provider 配置错误转换成可读 JSON,避免人工烟测时只看到裸 500
 * 或完整堆栈。正式 API 错误模型会在后续协议层统一。</p>
 */
@ControllerAdvice(assignableTypes = ProviderTestController.class)
public class GlobalExceptionHandler {

    /**
     * 处理 provider 配置不完整等业务配置错误。
     *
     * @param exception 配置错误
     * @return HTTP 400 + 可读错误码和消息
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "invalid_configuration",
                        "message", exception.getMessage()
                ));
    }

    /**
     * 处理未知 provider id 等请求参数错误。
     *
     * @param exception 参数错误
     * @return HTTP 400 + 可读错误码和消息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "invalid_request",
                        "message", exception.getMessage()
                ));
    }

    /**
     * 处理请求体 Bean Validation 错误。
     *
     * @param exception 校验错误
     * @return HTTP 400 + 可读错误码和消息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "invalid_request",
                        "message", "请求体字段校验失败:" + exception.getFieldErrors()
                ));
    }
}
