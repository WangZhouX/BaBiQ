package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.DesktopSessionTokenProvider;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Set;
import java.util.stream.Collectors;

/** Dedicated origin/loopback/desktop-session gate for binary business endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessLoopbackHttpSecurityFilter extends OncePerRequestFilter {
    public static final String CONNECTION_ATTRIBUTE = BusinessLoopbackHttpSecurityFilter.class.getName() + ".connection";
    public static final String LEASE_ATTRIBUTE = BusinessLoopbackHttpSecurityFilter.class.getName() + ".lease";
    public static final String UPLOAD_CLAIM_ATTRIBUTE = BusinessLoopbackHttpSecurityFilter.class.getName() + ".uploadClaim";

    private final DesktopSessionTokenProvider tokenProvider;
    private final BusinessDesktopConnectionRegistry connections;
    private final BusinessOaSessionRegistry sessions;
    private final BusinessAttachmentTicketService tickets;
    private final Set<String> allowedOrigins;

    public BusinessLoopbackHttpSecurityFilter(DesktopSessionTokenProvider tokenProvider,
                                               BusinessDesktopConnectionRegistry connections,
                                               BusinessOaSessionRegistry sessions,
                                               BusinessAttachmentTicketService tickets,
                                               @Value("${babiq.business.allowed-origins:http://127.0.0.1}") String allowedOrigins) {
        this.tokenProvider = tokenProvider;
        this.connections = connections;
        this.sessions = sessions;
        this.tickets = tickets;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.startsWith("/business/resources/") || path.startsWith("/business/attachments/uploads/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isSafeRequestShape(request) || !isLoopback(request.getRemoteAddr())
                || !isLoopbackHost(request.getHeader("Host"), request.getLocalPort())
                || !isExactOrigin(request.getHeader("Origin"), request.getHeader("Host"),
                request.getLocalPort(), allowedOrigins)
                || !tokenProvider.matches(bearer(request.getHeader("Authorization")))) {
            reject(response, "BUSINESS_RESOURCE_UNAVAILABLE");
            return;
        }
        String instanceId = request.getHeader("X-Desktop-Instance-Id");
        String sessionId = request.getHeader("X-Desktop-Session-Id");
        TrustedDesktopConnection connection = connections.findByDesktopSessionId(sessionId)
                .filter(value -> value.desktopInstanceId().equals(instanceId))
                .orElse(null);
        if (connection == null) {
            reject(response, "BUSINESS_AUTH_REQUIRED");
            return;
        }
        ReadyOaSessionLease lease;
        try {
            lease = sessions.captureReady(connection);
        } catch (RuntimeException unavailable) {
            reject(response, "BUSINESS_AUTH_REQUIRED");
            return;
        }
        request.setAttribute(CONNECTION_ATTRIBUTE, connection);
        request.setAttribute(LEASE_ATTRIBUTE, lease);
        BusinessAttachmentTicketService.UploadClaim uploadClaim = null;
        if (request.getRequestURI().startsWith("/business/attachments/uploads/")) {
            if (!"POST".equals(request.getMethod())) {
                reject(response, "BUSINESS_RESOURCE_UNAVAILABLE");
                return;
            }
            String batchId = request.getRequestURI()
                    .substring("/business/attachments/uploads/".length());
            if (batchId.isBlank() || batchId.contains("/")
                    || request.getHeader("X-Business-Upload-Ticket") == null) {
                reject(response, "BUSINESS_RESOURCE_UNAVAILABLE");
                return;
            }
            try {
                uploadClaim = tickets.claim(batchId,
                        request.getHeader("X-Business-Upload-Ticket"), connection, lease);
                request.setAttribute(UPLOAD_CLAIM_ATTRIBUTE, uploadClaim);
            } catch (RuntimeException unavailable) {
                reject(response, "BUSINESS_RESOURCE_UNAVAILABLE");
                return;
            }
        }
        boolean chainReturned = false;
        try {
            chain.doFilter(request, response);
            chainReturned = true;
        } finally {
            if (uploadClaim != null && (!chainReturned
                    || response.getStatus() < 200 || response.getStatus() >= 300)) {
                tickets.reject(uploadClaim);
            }
        }
    }

    private static boolean isSafeRequestShape(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return false;
        if (request.getHeader("Cookie") != null || request.getQueryString() != null) return false;
        return !hasUnsafeForwardingHeader(request);
    }

    static boolean hasUnsafeForwardingHeader(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return false;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null && (name.equalsIgnoreCase("Forwarded")
                    || name.regionMatches(true, 0, "X-Forwarded-", 0, "X-Forwarded-".length()))) {
                return true;
            }
        }
        return false;
    }

    static boolean isExactOrigin(String origin, String host, int localPort, Set<String> allowlist) {
        if (origin == null || host == null || localPort <= 0) return false;
        try {
            URI uri = URI.create(origin);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPath() != null && !uri.getPath().isEmpty()
                    || uri.getPort() != localPort || uri.getHost() == null) return false;
            String expectedHost = uri.getHost().contains(":")
                    ? "[" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + "]:" + localPort
                    : uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + localPort;
            if (!expectedHost.equals(host.strip().toLowerCase(java.util.Locale.ROOT))) return false;
            for (String allowed : allowlist) {
                URI base = URI.create(allowed);
                if ("http".equalsIgnoreCase(base.getScheme())
                        && base.getHost() != null
                        && base.getHost().equalsIgnoreCase(uri.getHost())
                        && (base.getPort() == -1 || base.getPort() == localPort)) return true;
            }
            return false;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean isLoopback(String remote) {
        if (remote == null || remote.isBlank()) return false;
        String normalized = remote.strip();
        if (normalized.startsWith("::ffff:")) normalized = normalized.substring("::ffff:".length());
        try { return InetAddress.getByName(normalized).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private static boolean isLoopbackHost(String host, int localPort) {
        if (host == null || host.isBlank()) return false;
        String value = host.strip().toLowerCase(java.util.Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return false;
        String rawPort = value.substring(colon + 1);
        int port;
        try { port = Integer.parseInt(rawPort); }
        catch (NumberFormatException ignored) { return false; }
        if (port <= 0 || port > 65_535 || localPort > 0 && localPort != port) return false;
        String name = value.substring(0, colon);
        return name.equals("127.0.0.1") || name.equals("localhost");
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String token = authorization.substring("Bearer ".length()).strip();
        return token.isBlank() ? null : token;
    }

    static void reject(HttpServletResponse response, String businessCode) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("X-Business-Code", businessCode);
        response.setContentType("application/json");
        String correlationId = java.util.UUID.randomUUID().toString();
        byte[] body = ("{\"businessCode\":\"" + businessCode
                + "\",\"correlationId\":\"" + correlationId + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
