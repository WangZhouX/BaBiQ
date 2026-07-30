package com.wzx.babiq.server.business.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.UUID;

/** Keeps multipart and proxy failures bodyless and free of paths, tickets, and OA response text. */
@RestControllerAdvice
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessUploadExceptionHandler {
    @ExceptionHandler(BusinessAttachmentTicketService.TicketUnavailableException.class)
    public ResponseEntity<ErrorBody> unavailable(BusinessAttachmentTicketService.TicketUnavailableException ignored) {
        return response(404, "BUSINESS_RESOURCE_UNAVAILABLE");
    }

    @ExceptionHandler(BusinessAttachmentTicketService.TicketRejectedException.class)
    public ResponseEntity<ErrorBody> rejected(BusinessAttachmentTicketService.TicketRejectedException ignored) {
        return response(400, "BUSINESS_ATTACHMENT_REJECTED");
    }

    @ExceptionHandler(BusinessAttachmentUploadController.BusinessUploadRejectedException.class)
    public ResponseEntity<ErrorBody> rejected(BusinessAttachmentUploadController.BusinessUploadRejectedException ignored) {
        return response(400, "BUSINESS_ATTACHMENT_REJECTED");
    }

    @ExceptionHandler(BusinessAttachmentUploadController.BusinessUploadUnavailableException.class)
    public ResponseEntity<ErrorBody> unavailable(BusinessAttachmentUploadController.BusinessUploadUnavailableException ignored) {
        return response(503, "BUSINESS_REMOTE_UNAVAILABLE");
    }

    @ExceptionHandler(BusinessAttachmentUploadController.BusinessUploadOutcomeUnknownException.class)
    public ResponseEntity<ErrorBody> outcomeUnknown(BusinessAttachmentUploadController.BusinessUploadOutcomeUnknownException ignored) {
        return response(502, "BUSINESS_ATTACHMENT_OUTCOME_UNKNOWN");
    }

    @ExceptionHandler({MultipartException.class, MaxUploadSizeExceededException.class,
            MissingRequestHeaderException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return response(400, "BUSINESS_ATTACHMENT_REJECTED");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> fallback(Exception ignored) {
        return response(503, "BUSINESS_REMOTE_UNAVAILABLE");
    }

    private static ResponseEntity<ErrorBody> response(int status, String code) {
        ErrorBody body = new ErrorBody(code, UUID.randomUUID().toString());
        return ResponseEntity.status(status).header("X-Business-Code", code).body(body);
    }

    public record ErrorBody(String businessCode, String correlationId) {
        @Override public String toString() {
            return "ErrorBody(businessCode=" + businessCode + ", correlationId=[REDACTED])";
        }
    }
}
