package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 对业务桌面回环 WebSocket 执行 Bearer 与桌面会话头认证。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessDesktopHandshakeInterceptor implements HandshakeInterceptor {

    private static final int DEFAULT_MAX_REQUEST_LEASES = 1024;

    public static final String DESKTOP_INSTANCE_ID_ATTRIBUTE =
            "babiq.business.desktopInstanceId";
    public static final String DESKTOP_SESSION_ID_ATTRIBUTE =
            "babiq.business.desktopSessionId";
    public static final String RESERVATION_ID_ATTRIBUTE =
            "babiq.business.reservationId";

    private final DesktopSessionTokenProvider tokenProvider;
    private final BusinessDesktopConnectionRegistry connectionRegistry;
    private final Set<String> allowedOrigins;
    private final Map<ServerHttpRequest, RequestLease> requestLeases = new IdentityHashMap<>();
    private final Clock clock;
    private final Duration requestLeaseTtl;
    private final int maxRequestLeases;

    @Autowired
    public BusinessDesktopHandshakeInterceptor(
            DesktopSessionTokenProvider tokenProvider,
            BusinessDesktopConnectionRegistry connectionRegistry,
            BusinessDesktopModeProperties properties) {
        this(tokenProvider,
                connectionRegistry,
                properties,
                Clock.systemUTC(),
                properties.acceptTimeout(),
                DEFAULT_MAX_REQUEST_LEASES);
    }

    BusinessDesktopHandshakeInterceptor(
            DesktopSessionTokenProvider tokenProvider,
            BusinessDesktopConnectionRegistry connectionRegistry,
            BusinessDesktopModeProperties properties,
            Clock clock,
            Duration requestLeaseTtl,
            int maxRequestLeases) {
        this.tokenProvider = tokenProvider;
        this.connectionRegistry = connectionRegistry;
        this.clock = clock;
        this.requestLeaseTtl = requestLeaseTtl;
        this.maxRequestLeases = maxRequestLeases;
        this.allowedOrigins = Stream.of(properties.allowedOrigins().split(","))
                .map(String::strip)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        removeExpiredRequestLeases();
        String token = bearerToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (!tokenProvider.matches(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String desktopInstanceId = request.getHeaders().getFirst("X-Desktop-Instance-Id");
        String desktopSessionId = request.getHeaders().getFirst("X-Desktop-Session-Id");
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (!isUuid(desktopInstanceId)
                || !isUuid(desktopSessionId)
                || !isLoopback(request.getRemoteAddress())
                || !allowedOrigins.contains(origin)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        try {
            String reservationId = connectionRegistry.reserve(desktopInstanceId, desktopSessionId);
            if (!registerRequestLease(request, reservationId)) {
                connectionRegistry.cancelReservation(reservationId);
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }
            attributes.put(DESKTOP_INSTANCE_ID_ATTRIBUTE, desktopInstanceId);
            attributes.put(DESKTOP_SESSION_ID_ATTRIBUTE, desktopSessionId);
            attributes.put(RESERVATION_ID_ATTRIBUTE, reservationId);
            return true;
        } catch (IllegalStateException exception) {
            response.setStatusCode(HttpStatus.CONFLICT);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        RequestLease requestLease;
        synchronized (requestLeases) {
            removeExpiredRequestLeasesLocked();
            requestLease = requestLeases.remove(request);
        }
        if (exception != null && requestLease != null) {
            connectionRegistry.cancelReservation(requestLease.reservationId());
        }
    }

    private boolean registerRequestLease(ServerHttpRequest request, String reservationId) {
        synchronized (requestLeases) {
            removeExpiredRequestLeasesLocked();
            if (requestLeases.size() >= maxRequestLeases) {
                return false;
            }
            requestLeases.put(request, new RequestLease(
                    reservationId, clock.instant().plus(requestLeaseTtl)));
            return true;
        }
    }

    private void removeExpiredRequestLeases() {
        synchronized (requestLeases) {
            removeExpiredRequestLeasesLocked();
        }
    }

    private void removeExpiredRequestLeasesLocked() {
        Instant now = clock.instant();
        requestLeases.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length());
        return token.isBlank() ? null : token;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isLoopback(InetSocketAddress remoteAddress) {
        return remoteAddress != null
                && remoteAddress.getAddress() != null
                && remoteAddress.getAddress().isLoopbackAddress();
    }

    private record RequestLease(String reservationId, Instant expiresAt) {
    }
}
