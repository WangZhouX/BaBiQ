package com.wzx.babiq.server.application.tool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/** 激活前缓存进度，激活时由唯一 drainer 保证 FIFO 派发。 */
final class DeferredProgressListener<T> {

    private enum State {
        INACTIVE,
        DRAINING,
        ACTIVE
    }

    private final Consumer<T> delegate;
    private final Deque<T> queued = new ArrayDeque<>();
    private State state = State.INACTIVE;

    DeferredProgressListener(Consumer<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    void accept(T progress) {
        synchronized (this) {
            if (state != State.ACTIVE) {
                queued.addLast(progress);
                return;
            }
        }
        deliver(progress);
    }

    void activate() {
        synchronized (this) {
            if (state != State.INACTIVE) {
                return;
            }
            state = State.DRAINING;
        }
        while (true) {
            T progress;
            synchronized (this) {
                progress = queued.pollFirst();
                if (progress == null) {
                    state = State.ACTIVE;
                    return;
                }
            }
            deliver(progress);
        }
    }

    private void deliver(T progress) {
        try {
            delegate.accept(progress);
        } catch (RuntimeException ignored) {
            // UI progress is observational; one callback failure must not stop later states.
        }
    }
}
