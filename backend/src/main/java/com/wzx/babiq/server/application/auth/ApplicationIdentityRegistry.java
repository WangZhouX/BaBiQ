package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以真实 WebSocket session 为键保存业务桌面身份及严格递增的 identity epoch。
 *
 * <p>可信桌面连接来自握手链路，identity 消息只提供业务身份候选值；两者完全匹配后
 * 才安装快照。登出仍保留 epoch 水位，防止旧身份消息重新生效。</p>
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class ApplicationIdentityRegistry {

    private static final IdentityChangeListener NOOP_LISTENER = (connection, oldIdentity, newIdentity) -> {
    };

    private final Map<String, ConnectionIdentityState> states = new HashMap<>();
    private final IdentityChangeListener changeListener;

    /** 无 Spring 容器的测试和直接调用使用无副作用 listener。 */
    public ApplicationIdentityRegistry() {
        this(NOOP_LISTENER);
    }

    /** Spring 生产构造器按顺序组合所有身份变化 listener，供后续工作失效链路接入。 */
    @Autowired
    public ApplicationIdentityRegistry(ObjectProvider<IdentityChangeListener> listenerProvider) {
        this(listenerProvider.orderedStream()
                .reduce(NOOP_LISTENER, ApplicationIdentityRegistry::compose));
    }

    /** 测试或组合根可注入身份变更回调，用于使尚未执行的工作失效。 */
    public ApplicationIdentityRegistry(IdentityChangeListener changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    /** 首次绑定必须是已登录身份；成功 bind 不触发身份变更回调。 */
    public synchronized TrustedBusinessIdentity bind(
            TrustedDesktopConnection connection,
            ApplicationIdentityMessage message) {
        validateConnectionScope(connection, message);
        ApplicationProtocolValidator.validate(message);
        if (!message.authenticated()) {
            throw new IllegalArgumentException("Identity bind requires authenticated=true");
        }
        if (states.containsKey(connection.webSocketSessionId())) {
            throw new IllegalStateException("Identity is already bound to this WebSocket connection");
        }

        TrustedBusinessIdentity identity = trustedIdentity(connection, message);
        states.put(connection.webSocketSessionId(),
                new ConnectionIdentityState(connection, message.identityEpoch(), identity, false));
        return identity;
    }

    /**
     * 用严格更高 epoch 更新身份；登出会安装空身份但继续保留新的 epoch 水位。
     */
    public Optional<TrustedBusinessIdentity> update(
            TrustedDesktopConnection connection,
            ApplicationIdentityMessage message) {
        return update(connection, message, () -> {
        });
    }

    /**
     * 在身份切换期间先 fail-closed，再执行失效回调和旧作用域清理，最后原子发布新身份。
     *
     * <p>listener 不持 registry 或 connection 锁；cleanup 在 connection 锁内执行，
     * 与目录/上下文 handler 共用同一互斥边界。任一步失败都会恢复旧身份和 epoch。</p>
     */
    public Optional<TrustedBusinessIdentity> update(
            TrustedDesktopConnection connection,
            ApplicationIdentityMessage message,
            Runnable beforeCommitCleanup) {
        validateConnectionScope(connection, message);
        ApplicationProtocolValidator.validate(message);
        Objects.requireNonNull(beforeCommitCleanup, "beforeCommitCleanup");
        ConnectionIdentityState current;
        ConnectionIdentityState transition;
        TrustedBusinessIdentity next;
        synchronized (connection) {
            synchronized (this) {
                current = requireState(connection);
                if (current.transitioning()) {
                    throw new IllegalStateException("Identity transition is already in progress");
                }
                if (message.identityEpoch() <= current.identityEpoch()) {
                    throw new IllegalArgumentException("identityEpoch must strictly increase");
                }
                next = message.authenticated() ? trustedIdentity(connection, message) : null;
                transition = new ConnectionIdentityState(
                        connection, current.identityEpoch(), current.identity(), true);
                states.put(connection.webSocketSessionId(), transition);
            }
        }

        try {
            // 外部 listener 不持任何内部锁，transitioning 状态让所有读取方 fail-closed。
            changeListener.onIdentityChanged(connection, current.identity(), next);
            synchronized (connection) {
                beforeCommitCleanup.run();
                synchronized (this) {
                    ConnectionIdentityState actual = states.get(connection.webSocketSessionId());
                    if (actual != transition) {
                        throw new IllegalStateException("Identity changed concurrently during update");
                    }
                    states.put(connection.webSocketSessionId(),
                            new ConnectionIdentityState(connection, message.identityEpoch(), next, false));
                }
            }
            return Optional.ofNullable(next);
        } catch (RuntimeException | Error exception) {
            rollbackTransition(connection, transition, current);
            throw exception;
        }
    }

    /** 返回指定 WebSocket 当前已认证身份；登出和未知连接均为空。 */
    public synchronized Optional<TrustedBusinessIdentity> find(String webSocketSessionId) {
        ConnectionIdentityState state = states.get(webSocketSessionId);
        return state == null || state.transitioning()
                ? Optional.empty()
                : Optional.ofNullable(state.identity());
    }

    /** 仅在连接记录完全匹配时返回身份，避免复用 session id 的陈旧连接读取状态。 */
    public synchronized Optional<TrustedBusinessIdentity> current(TrustedDesktopConnection connection) {
        ConnectionIdentityState state = states.get(connection.webSocketSessionId());
        if (state == null || state.transitioning() || !state.connection().equals(connection)) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.identity());
    }

    /** 供 JSON-RPC access policy 做最小认证状态判断。 */
    public boolean isAuthenticated(String webSocketSessionId) {
        return find(webSocketSessionId).isPresent();
    }

    /** 连接关闭时清除身份和 epoch 水位，使新的握手连接从首次 bind 重新开始。 */
    public synchronized void clear(TrustedDesktopConnection connection) {
        ConnectionIdentityState current = states.get(connection.webSocketSessionId());
        if (current != null && current.connection().equals(connection)) {
            states.remove(connection.webSocketSessionId());
        }
    }

    private ConnectionIdentityState requireState(TrustedDesktopConnection connection) {
        ConnectionIdentityState state = states.get(connection.webSocketSessionId());
        if (state == null || !state.connection().equals(connection)) {
            throw new IllegalArgumentException("Identity must be bound before update");
        }
        return state;
    }

    private void rollbackTransition(
            TrustedDesktopConnection connection,
            ConnectionIdentityState transition,
            ConnectionIdentityState previous) {
        synchronized (connection) {
            synchronized (this) {
                if (states.get(connection.webSocketSessionId()) == transition) {
                    states.put(connection.webSocketSessionId(), previous);
                }
            }
        }
    }

    private void validateConnectionScope(
            TrustedDesktopConnection connection,
            ApplicationIdentityMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        if (!connection.desktopInstanceId().equals(message.desktopInstanceId())
                || !connection.desktopSessionId().equals(message.desktopSessionId())) {
            throw new IllegalArgumentException("Identity message does not match trusted desktop connection");
        }
    }

    private TrustedBusinessIdentity trustedIdentity(
            TrustedDesktopConnection connection,
            ApplicationIdentityMessage message) {
        return new TrustedBusinessIdentity(
                connection.reservationId(),
                connection.webSocketSessionId(),
                connection.desktopInstanceId(),
                connection.desktopSessionId(),
                message.authSessionId(),
                message.identityEpoch(),
                message.userId(),
                message.tenantId(),
                message.platformId(),
                message.roles(),
                message.permissions());
    }

    private static IdentityChangeListener compose(
            IdentityChangeListener first,
            IdentityChangeListener second) {
        return (connection, oldIdentity, newIdentity) -> {
            first.onIdentityChanged(connection, oldIdentity, newIdentity);
            second.onIdentityChanged(connection, oldIdentity, newIdentity);
        };
    }

    private record ConnectionIdentityState(
            TrustedDesktopConnection connection,
            long identityEpoch,
            TrustedBusinessIdentity identity,
            boolean transitioning) {
    }

    /** 身份成功更新后的失效通知；newIdentity 为 null 表示已登出。 */
    @FunctionalInterface
    public interface IdentityChangeListener {
        void onIdentityChanged(
                TrustedDesktopConnection connection,
                TrustedBusinessIdentity oldIdentity,
                TrustedBusinessIdentity newIdentity);
    }
}
