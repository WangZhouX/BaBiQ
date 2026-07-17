package com.wzx.babiq.server.application.tool;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 保存一次 {@code application_action} 工具调用的不可变关联信息。
 *
 * <p>上下文只在拦截器调用下游工具的动态范围内可见。栈结构同时保证嵌套调用
 * 能恢复外层值，而 {@link ThreadLocal} 保证不同执行线程不会串联租户身份。</p>
 */
public final class ApplicationToolInvocationContext {

    private static final ThreadLocal<Deque<Invocation>> INVOCATIONS = new ThreadLocal<>();

    private ApplicationToolInvocationContext() {
    }

    /** 返回当前最内层工具调用；普通工具或已离开下游调用范围时为空。 */
    public static Optional<Invocation> current() {
        Deque<Invocation> stack = INVOCATIONS.get();
        return stack == null ? Optional.empty() : Optional.ofNullable(stack.peek());
    }

    /**
     * 安装一个动态作用域，并返回必须在 {@code finally} 中关闭的句柄。
     */
    public static Scope install(Invocation invocation) {
        if (invocation == null) {
            throw new IllegalArgumentException("invocation must not be null");
        }
        Deque<Invocation> stack = INVOCATIONS.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            INVOCATIONS.set(stack);
        }
        stack.push(invocation);
        return new Scope(stack, invocation);
    }

    /** application_action 工具需要的最小且不可变的关联快照。 */
    public record Invocation(
            String toolCallId,
            String threadId,
            String turnId,
            BusinessIdentityScope businessIdentityScope
    ) {
        public Invocation {
            requireText(toolCallId, "toolCallId");
            requireText(threadId, "threadId");
            requireText(turnId, "turnId");
            if (businessIdentityScope == null) {
                throw new IllegalArgumentException("businessIdentityScope must not be null");
            }
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    /** 关闭后恢复外层上下文；重复关闭不改变栈。 */
    public static final class Scope implements AutoCloseable {

        private final Deque<Invocation> stack;
        private final Invocation invocation;
        private boolean closed;

        private Scope(Deque<Invocation> stack, Invocation invocation) {
            this.stack = stack;
            this.invocation = invocation;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Invocation current = stack.poll();
            if (current != invocation) {
                stack.clear();
                INVOCATIONS.remove();
                throw new IllegalStateException("application tool invocation scopes closed out of order");
            }
            if (stack.isEmpty()) {
                INVOCATIONS.remove();
            }
        }
    }
}
