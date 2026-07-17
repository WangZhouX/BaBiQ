package com.wzx.babiq.server.application.api;

/** The desktop returned a correlated negative acknowledgement, so execution uncertainty is not implied. */
public final class ConfirmedActionRequestRejection extends RuntimeException {
    private final String errorCode;

    public ConfirmedActionRequestRejection(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
