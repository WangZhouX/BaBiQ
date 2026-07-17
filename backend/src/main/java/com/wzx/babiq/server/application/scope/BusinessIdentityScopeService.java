package com.wzx.babiq.server.application.scope;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** 在 JSON-RPC 请求边界把可信桌面连接解析成不可变业务身份作用域。 */
@Component
public final class BusinessIdentityScopeService {

    private final boolean businessEnabled;
    private final BusinessDesktopConnectionRegistry connections;
    private final ApplicationIdentityRegistry identities;

    /** 生产构造器允许普通 profile 缺少仅业务模式注册的两个 registry。 */
    @Autowired
    public BusinessIdentityScopeService(
            BusinessDesktopModeProperties properties,
            ObjectProvider<BusinessDesktopConnectionRegistry> connectionProvider,
            ObjectProvider<ApplicationIdentityRegistry> identityProvider) {
        this(properties.enabled(), connectionProvider.getIfAvailable(), identityProvider.getIfAvailable());
    }

    /** 测试构造器显式选择普通或业务模式。 */
    BusinessIdentityScopeService(
            boolean businessEnabled,
            BusinessDesktopConnectionRegistry connections,
            ApplicationIdentityRegistry identities) {
        this.businessEnabled = businessEnabled;
        this.connections = connections;
        this.identities = identities;
    }

    /**
     * 只在请求入口解析一次身份；返回值随后随 Thread/Turn 显式传播。
     *
     * <p>连接注册表和身份注册表必须对同一 finalized connection 给出一致结果。
     * 身份切换的过渡期由 {@code identities.current(connection)} 自动 fail-closed。</p>
     */
    public BusinessIdentityScope resolve(WebSocketSession session) {
        if (!businessEnabled) {
            return BusinessIdentityScope.UNSCOPED;
        }
        if (connections == null || identities == null || session == null
                || isBlank(session.getId()) || session.getAttributes() == null) {
            throw unavailable();
        }
        Map<String, Object> attributes = session.getAttributes();
        String reservationId = attribute(attributes, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE);
        String desktopInstanceId = attribute(
                attributes, BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE);
        String desktopSessionId = attribute(
                attributes, BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE);
        TrustedDesktopConnection connection = connections.findByDesktopSessionId(desktopSessionId)
                .orElseThrow(BusinessIdentityScopeService::unavailable);
        requireCurrentConnection(connection, reservationId, desktopInstanceId, desktopSessionId, session.getId());
        TrustedBusinessIdentity identity = identities.current(connection)
                .orElseThrow(BusinessIdentityScopeService::unavailable);
        requireCurrentIdentity(connection, identity);
        return BusinessIdentityScope.scoped(
                identity.desktopInstanceId(), identity.desktopSessionId(), identity.authSessionId(),
                identity.identityEpoch(), identity.userId(), identity.tenantId(), identity.platformId());
    }

    /**
     * 用 Turn 创建时冻结的七元身份定位唯一的当前连接；任一字段变化都拒绝复用。
     *
     * <p>这里不是读取“当前租户”，而是验证原快照仍精确对应同一桌面会话和认证身份。</p>
     */
    public Optional<ActiveBusinessIdentity> resolveActive(BusinessIdentityScope scope) {
        if (!businessEnabled || connections == null || identities == null || scope == null || !scope.scoped()) {
            return Optional.empty();
        }
        TrustedDesktopConnection connection = connections.findByDesktopSessionId(scope.desktopSessionId())
                .filter(candidate -> candidate.desktopInstanceId().equals(scope.desktopInstanceId()))
                .orElse(null);
        if (connection == null) {
            return Optional.empty();
        }
        TrustedBusinessIdentity identity = identities.current(connection).orElse(null);
        if (identity == null || !scope.equals(toScope(identity))) {
            return Optional.empty();
        }
        return Optional.of(new ActiveBusinessIdentity(connection, identity));
    }

    /**
     * 在连接级身份切换互斥区间内读取当前作用域数据，防止旧 Turn 拼接新身份快照。
     *
     * <p>锁顺序与身份更新和目录发布一致：connection -> identity registry -> downstream registry。</p>
     */
    public <T> Optional<T> withActiveConnectionScope(
            BusinessIdentityScope scope,
            Function<ActiveBusinessIdentity, T> reader) {
        if (!businessEnabled || connections == null || identities == null || scope == null
                || !scope.scoped() || reader == null) {
            return Optional.empty();
        }
        TrustedDesktopConnection connection = connections.findByDesktopSessionId(scope.desktopSessionId())
                .filter(candidate -> candidate.desktopInstanceId().equals(scope.desktopInstanceId()))
                .orElse(null);
        if (connection == null) {
            return Optional.empty();
        }
        synchronized (connection) {
            try {
                TrustedBusinessIdentity identity = identities.current(connection).orElse(null);
                if (identity == null || !scope.equals(toScope(identity))) {
                    return Optional.empty();
                }
                T result = reader.apply(new ActiveBusinessIdentity(connection, identity));
                TrustedBusinessIdentity afterRead = identities.current(connection).orElse(null);
                if (afterRead == null || !scope.equals(toScope(afterRead)) || !identity.equals(afterRead)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(result);
            } catch (RuntimeException identityOrReadFailure) {
                return Optional.empty();
            }
        }
    }

    private static BusinessIdentityScope toScope(TrustedBusinessIdentity identity) {
        return BusinessIdentityScope.scoped(
                identity.desktopInstanceId(), identity.desktopSessionId(), identity.authSessionId(),
                identity.identityEpoch(), identity.userId(), identity.tenantId(), identity.platformId());
    }

    /** 精确匹配的连接和可信身份，仅供服务端动作桥使用。 */
    public record ActiveBusinessIdentity(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity) {
    }

    private static void requireCurrentConnection(
            TrustedDesktopConnection connection,
            String reservationId,
            String desktopInstanceId,
            String desktopSessionId,
            String webSocketSessionId) {
        if (!connection.reservationId().equals(reservationId)
                || !connection.desktopInstanceId().equals(desktopInstanceId)
                || !connection.desktopSessionId().equals(desktopSessionId)
                || !connection.webSocketSessionId().equals(webSocketSessionId)) {
            throw unavailable();
        }
    }

    private static void requireCurrentIdentity(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity) {
        if (!identity.reservationId().equals(connection.reservationId())
                || !identity.webSocketSessionId().equals(connection.webSocketSessionId())
                || !identity.desktopInstanceId().equals(connection.desktopInstanceId())
                || !identity.desktopSessionId().equals(connection.desktopSessionId())) {
            throw unavailable();
        }
    }

    private static String attribute(Map<String, Object> attributes, String name) {
        Object value = attributes.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable();
        }
        return text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("current authenticated business identity is unavailable");
    }
}
