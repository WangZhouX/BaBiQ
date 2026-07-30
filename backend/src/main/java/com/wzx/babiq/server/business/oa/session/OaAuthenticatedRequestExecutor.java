package com.wzx.babiq.server.business.oa.session;

import java.util.Objects;
import java.util.function.Function;

/** Executes an OA call with credentials loaded only on the server and a generation revalidation on return. */
public final class OaAuthenticatedRequestExecutor {
    private final BusinessOaSessionRegistry registry;
    private final OaSessionCredentialStore credentials;
    private final OaTokenRefreshCoordinator refreshCoordinator;
    private final OaSessionTerminalizer terminalizer;

    public OaAuthenticatedRequestExecutor(BusinessOaSessionRegistry registry, OaSessionCredentialStore credentials,
                                          OaTokenRefreshCoordinator refreshCoordinator,
                                          OaSessionTerminalizer terminalizer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.refreshCoordinator = Objects.requireNonNull(refreshCoordinator, "refreshCoordinator");
        this.terminalizer = Objects.requireNonNull(terminalizer, "terminalizer");
    }

    public <T> T execute(ReadyOaSessionLease lease, RequestKind kind, CredentialOperation<T> operation) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        ensureCurrent(lease);
        T result;
        try {
            result = invoke(lease, operation);
        } catch (RuntimeException failure) {
            OaRemoteRequestException.TerminalReason reason = terminalReason(failure);
            if (reason == null) throw propagate(failure);
            if (reason == OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED
                    || kind == RequestKind.WRITE) {
                throw terminate(lease, reason, failure);
            }
            return retryReadOnce(lease, operation);
        }
        if (!registry.isCurrent(lease)) throw new StaleLeaseException();
        return result;
    }

    private <T> T retryReadOnce(ReadyOaSessionLease lease, CredentialOperation<T> operation) {
        ReadyOaSessionLease refreshed;
        try {
            refreshed = refreshCoordinator.refresh(lease).join();
        } catch (RuntimeException failure) {
            OaRemoteRequestException.TerminalReason reason = terminalReason(failure);
            if (reason != null) throw terminate(lease, reason, failure);
            throw propagate(failure);
        }
        ensureCurrent(refreshed);
        T result;
        try {
            result = invoke(refreshed, operation);
        } catch (RuntimeException failure) {
            OaRemoteRequestException.TerminalReason reason = terminalReason(failure);
            if (reason != null) throw terminate(refreshed, reason, failure);
            throw propagate(failure);
        }
        if (!registry.isCurrent(refreshed)) throw new StaleLeaseException();
        return result;
    }

    private RuntimeException terminate(ReadyOaSessionLease lease,
                                       OaRemoteRequestException.TerminalReason reason,
                                       RuntimeException failure) {
        terminalizer.terminate(lease, reason);
        Throwable unwrapped = unwrapCompletion(failure);
        if (unwrapped instanceof OaRemoteRequestException remote
                && remote.terminalReason() == reason) {
            return remote;
        }
        return reason == OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED
                ? OaRemoteRequestException.membershipExpired(1002010000)
                : OaRemoteRequestException.authenticationExpired(401);
    }

    private static OaRemoteRequestException.TerminalReason terminalReason(Throwable failure) {
        Throwable unwrapped = unwrapCompletion(failure);
        if (unwrapped instanceof OaRemoteRequestException remote) {
            return remote.terminalReason();
        }
        if (unwrapped instanceof com.wzx.babiq.server.business.oa.client.OaAuthenticationException authentication) {
            return switch (authentication.error()) {
                case AUTH_EXPIRED, INVALID_CREDENTIALS, ACCOUNT_NOT_FOUND ->
                        OaRemoteRequestException.TerminalReason.AUTH_EXPIRED;
                case MEMBER_EXPIRED -> OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED;
                default -> null;
            };
        }
        return null;
    }

    private static RuntimeException propagate(RuntimeException failure) {
        Throwable unwrapped = unwrapCompletion(failure);
        return unwrapped instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("OA request failed", unwrapped);
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private <T> T invoke(ReadyOaSessionLease lease, CredentialOperation<T> operation) {
        OaSessionCredentialStore.CredentialMaterial material = credentials.load(lease.activeCredentialRef());
        if (material == null) throw new IllegalStateException("OA session credential is unavailable");
        try {
            return operation.execute(material.accessToken());
        } finally {
            material.close();
        }
    }

    private void ensureCurrent(ReadyOaSessionLease lease) {
        if (!registry.isCurrent(lease)) throw new StaleLeaseException();
    }

    public enum RequestKind { READ, WRITE }
    @FunctionalInterface public interface CredentialOperation<T> { T execute(char[] accessToken); }
    public static final class StaleLeaseException extends IllegalStateException {
        public StaleLeaseException() { super("OA session lease is stale"); }
    }
}
