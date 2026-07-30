package com.wzx.babiq.server.business.api;
import com.wzx.babiq.server.business.oa.client.*;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
public final class BusinessRpcErrorMapper {
    private BusinessRpcErrorMapper() {}
    public record MappedError(int rpcCode, String businessCode, String message, boolean retryable) {}
    public static MappedError map(Throwable error) {
        error = unwrapCompletion(error);
        if (error instanceof OaRemoteRequestException remote) {
            if (remote.terminalReason() == OaRemoteRequestException.TerminalReason.AUTH_EXPIRED) {
                return new MappedError(-32014, "BUSINESS_AUTH_EXPIRED", "Business session authentication expired", false);
            }
            if (remote.terminalReason() == OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED) {
                return new MappedError(-32015, "BUSINESS_MEMBERSHIP_EXPIRED", "Business membership expired", false);
            }
            if (remote.ambiguousAfterSend()) {
                return new MappedError(-32032, "BUSINESS_OUTCOME_UNKNOWN", "Business operation outcome is unknown", false);
            }
            return new MappedError(-32040, "BUSINESS_REMOTE_UNAVAILABLE", "Remote service unavailable", true);
        }
        if (error instanceof IllegalStateException state) {
            if (state instanceof OaAuthenticatedRequestExecutor.StaleLeaseException) {
                return new MappedError(
                        -32016, "BUSINESS_SESSION_STALE", "Business session is stale", false);
            }
            String message = state.getMessage();
            MappedError mapped = message == null ? null : switch (message) {
                case "BUSINESS_OPERATION_OUTCOME_UNKNOWN", "BUSINESS_ATTACHMENT_CONSUME_FAILED" ->
                        new MappedError(-32032, "BUSINESS_OUTCOME_UNKNOWN", "Business operation outcome is unknown", false);
                case "BUSINESS_OPERATION_CONFLICT", "BUSINESS_OPERATION_IN_FLIGHT", "BUSINESS_CONFLICT" ->
                        new MappedError(-32031, "BUSINESS_CONFLICT", "Business operation conflicts with current state", false);
                case "BUSINESS_SESSION_STALE" ->
                        new MappedError(-32016, "BUSINESS_SESSION_STALE", "Business session is stale", false);
                case "OA session generation conflict", "OA session installation is stale",
                        "OA installation id mismatch", "OA installation owner mismatch",
                        "OA installation generation mismatch", "OA installation expired",
                        "OA session is revoked" ->
                        new MappedError(-32016, "BUSINESS_SESSION_STALE", "Business session is stale", false);
                case "BUSINESS_SESSION_NOT_ATTACHABLE" ->
                        new MappedError(-32019, "BUSINESS_SESSION_NOT_ATTACHABLE", "Authentication cannot be attached", false);
                default -> null;
            };
            if (mapped != null) return mapped;
        }
        if (error instanceof OaWorkbenchException workbench) {
            return switch (workbench.getMessage()) {
                case "REMOTE_TIMEOUT" -> new MappedError(-32042, "BUSINESS_REMOTE_TIMEOUT", "Remote service timeout", true);
                case "REMOTE_UNAVAILABLE" -> new MappedError(-32040, "BUSINESS_REMOTE_UNAVAILABLE", "Remote service unavailable", true);
                default -> new MappedError(-32043, "BUSINESS_REMOTE_PROTOCOL_ERROR", "Remote service protocol error", false);
            };
        }
        if (error instanceof OaAuthenticationException oa) return switch (oa.error()) {
            case AUTH_EXPIRED -> new MappedError(-32014,"BUSINESS_AUTH_EXPIRED","Business session authentication expired",false);
            case MEMBER_EXPIRED -> new MappedError(-32015,"BUSINESS_MEMBERSHIP_EXPIRED","Business membership expired",false);
            case REMOTE_UNAVAILABLE -> new MappedError(-32040,"BUSINESS_REMOTE_UNAVAILABLE","Remote service unavailable",true);
            case REMOTE_TIMEOUT -> new MappedError(-32042,"BUSINESS_REMOTE_TIMEOUT","Remote service timeout",true);
            case REMOTE_PROTOCOL_ERROR -> new MappedError(-32043,"BUSINESS_REMOTE_PROTOCOL_ERROR","Remote service protocol error",false);
            case INVALID_CREDENTIALS -> new MappedError(-32013,"BUSINESS_INVALID_CREDENTIALS","Invalid credentials",false);
            case ACCOUNT_NOT_FOUND -> new MappedError(-32011,"BUSINESS_ACCOUNT_NOT_FOUND","Account not found",false);
            case INVALID_PASSWORD_FORMAT -> new MappedError(-32030,"BUSINESS_INVALID_PASSWORD","Invalid password format",false);
        };
        return new MappedError(-32041,"PROTOCOL_ERROR","Internal server error",false);
    }

    private static Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
