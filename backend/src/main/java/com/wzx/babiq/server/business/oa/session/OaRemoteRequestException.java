package com.wzx.babiq.server.business.oa.session;

/** Stable remote request result used by the session-bound executor. */
public final class OaRemoteRequestException extends RuntimeException {
    private final int statusCode;
    private final TerminalReason terminalReason;
    private final boolean ambiguousAfterSend;

    private OaRemoteRequestException(String message, int statusCode, TerminalReason terminalReason,
                                     boolean ambiguousAfterSend) {
        super(message);
        this.statusCode = statusCode;
        this.terminalReason = terminalReason;
        this.ambiguousAfterSend = ambiguousAfterSend;
    }

    public static OaRemoteRequestException authenticationExpired(int statusCode) {
        if (statusCode != 401 && statusCode != 499) throw new IllegalArgumentException("statusCode must be 401 or 499");
        return new OaRemoteRequestException("OA_AUTH_EXPIRED", statusCode, TerminalReason.AUTH_EXPIRED, false);
    }

    public static OaRemoteRequestException membershipExpired(int businessCode) {
        if (businessCode != 1002010000) {
            throw new IllegalArgumentException("businessCode must be 1002010000");
        }
        return new OaRemoteRequestException(
                "OA_MEMBERSHIP_EXPIRED", businessCode, TerminalReason.MEMBERSHIP_EXPIRED, false);
    }

    public static OaRemoteRequestException networkFailure(boolean afterSend) {
        return new OaRemoteRequestException(afterSend ? "OA_OUTCOME_UNKNOWN" : "OA_REMOTE_UNAVAILABLE", 0,
                null, afterSend);
    }

    public int statusCode() { return statusCode; }
    public boolean authenticationExpired() { return terminalReason == TerminalReason.AUTH_EXPIRED; }
    public boolean terminal() { return terminalReason != null; }
    public TerminalReason terminalReason() { return terminalReason; }
    public boolean ambiguousAfterSend() { return ambiguousAfterSend; }

    public enum TerminalReason { AUTH_EXPIRED, MEMBERSHIP_EXPIRED }
}
