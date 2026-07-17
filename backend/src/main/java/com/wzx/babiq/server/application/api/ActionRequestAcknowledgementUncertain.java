package com.wzx.babiq.server.application.api;

/** Transport loss left the action request acknowledgement unknown. */
public final class ActionRequestAcknowledgementUncertain extends RuntimeException {
    public ActionRequestAcknowledgementUncertain(String message) {
        super(message);
    }

    public ActionRequestAcknowledgementUncertain(String message, Throwable cause) {
        super(message, cause);
    }
}
