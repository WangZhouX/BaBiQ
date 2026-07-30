package com.wzx.babiq.server.business.api;

import com.wzx.babiq.server.business.oa.client.OaAuthenticationError;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationException;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRpcErrorMapperTest {

    @Test
    void mapsStableRemoteErrorsWithoutReturningRemoteMessages() {
        BusinessRpcErrorMapper.MappedError unavailable = BusinessRpcErrorMapper.map(
                new OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE));
        BusinessRpcErrorMapper.MappedError timeout = BusinessRpcErrorMapper.map(
                new OaAuthenticationException(OaAuthenticationError.REMOTE_TIMEOUT));
        BusinessRpcErrorMapper.MappedError protocol = BusinessRpcErrorMapper.map(
                new OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR));

        assertThat(unavailable.rpcCode()).isEqualTo(-32040);
        assertThat(unavailable.businessCode()).isEqualTo("BUSINESS_REMOTE_UNAVAILABLE");
        assertThat(timeout.rpcCode()).isEqualTo(-32042);
        assertThat(protocol.rpcCode()).isEqualTo(-32043);
        assertThat(protocol.message()).doesNotContain("REMOTE_PROTOCOL_ERROR");
    }

    @Test
    void keepsExistingProtocolErrorCodeSeparateAndMarksRetryableFailures() {
        BusinessRpcErrorMapper.MappedError protocol = BusinessRpcErrorMapper.map(
                new IllegalArgumentException("malformed envelope"));

        assertThat(protocol.rpcCode()).isEqualTo(-32041);
        assertThat(protocol.businessCode()).isEqualTo("PROTOCOL_ERROR");
        assertThat(protocol.retryable()).isFalse();
        assertThat(BusinessRpcErrorMapper.map(
                new OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE)).retryable()).isTrue();
    }

    @Test
    void mapsAmbiguousRemoteWritesAndScheduleOperationStatesToStableBusinessCodes() {
        BusinessRpcErrorMapper.MappedError remoteUnknown = BusinessRpcErrorMapper.map(
                OaRemoteRequestException.networkFailure(true));
        BusinessRpcErrorMapper.MappedError operationUnknown = BusinessRpcErrorMapper.map(
                new IllegalStateException("BUSINESS_OPERATION_OUTCOME_UNKNOWN"));
        BusinessRpcErrorMapper.MappedError attachmentUnknown = BusinessRpcErrorMapper.map(
                new IllegalStateException("BUSINESS_ATTACHMENT_CONSUME_FAILED"));
        BusinessRpcErrorMapper.MappedError conflict = BusinessRpcErrorMapper.map(
                new IllegalStateException("BUSINESS_OPERATION_CONFLICT"));
        BusinessRpcErrorMapper.MappedError inFlight = BusinessRpcErrorMapper.map(
                new IllegalStateException("BUSINESS_OPERATION_IN_FLIGHT"));
        BusinessRpcErrorMapper.MappedError stale = BusinessRpcErrorMapper.map(
                new IllegalStateException("BUSINESS_SESSION_STALE"));
        BusinessRpcErrorMapper.MappedError notAttachable = BusinessRpcErrorMapper.map(
                new IllegalStateException("BUSINESS_SESSION_NOT_ATTACHABLE"));
        BusinessRpcErrorMapper.MappedError generationConflict = BusinessRpcErrorMapper.map(
                new IllegalStateException("OA session generation conflict"));
        BusinessRpcErrorMapper.MappedError installationStale = BusinessRpcErrorMapper.map(
                new IllegalStateException("OA session installation is stale"));

        assertThat(remoteUnknown.rpcCode()).isEqualTo(-32032);
        assertThat(remoteUnknown.businessCode()).isEqualTo("BUSINESS_OUTCOME_UNKNOWN");
        assertThat(remoteUnknown.retryable()).isFalse();
        assertThat(operationUnknown.rpcCode()).isEqualTo(-32032);
        assertThat(attachmentUnknown.rpcCode()).isEqualTo(-32032);
        assertThat(conflict.rpcCode()).isEqualTo(-32031);
        assertThat(inFlight.rpcCode()).isEqualTo(-32031);
        assertThat(stale.rpcCode()).isEqualTo(-32016);
        assertThat(notAttachable.rpcCode()).isEqualTo(-32019);
        assertThat(notAttachable.businessCode()).isEqualTo("BUSINESS_SESSION_NOT_ATTACHABLE");
        assertThat(notAttachable.message()).doesNotContain("handle", "session", "token");
        assertThat(generationConflict.rpcCode()).isEqualTo(-32016);
        assertThat(generationConflict.businessCode()).isEqualTo("BUSINESS_SESSION_STALE");
        assertThat(installationStale.rpcCode()).isEqualTo(-32016);
    }

    @Test
    void unwrapsCompletionExceptionBeforeMappingTerminalAuthenticationFailure() {
        BusinessRpcErrorMapper.MappedError mapped = BusinessRpcErrorMapper.map(
                new CompletionException(OaRemoteRequestException.authenticationExpired(401)));

        assertThat(mapped.rpcCode()).isEqualTo(-32014);
        assertThat(mapped.businessCode()).isEqualTo("BUSINESS_AUTH_EXPIRED");
        assertThat(mapped.retryable()).isFalse();
    }
}
