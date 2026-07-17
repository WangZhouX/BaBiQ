package com.wzx.babiq.server.application.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredProgressListenerTest {

    @Test
    void activation_drains_queued_and_concurrent_progress_in_fifo_order_once() throws Exception {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch startedA = new CountDownLatch(1);
        CountDownLatch finishA = new CountDownLatch(1);
        DeferredProgressListener<String> listener = new DeferredProgressListener<>(value -> {
            if ("A".equals(value)) {
                startedA.countDown();
                try {
                    assertThat(finishA.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            delivered.add(value);
        });
        listener.accept("A");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var activation = executor.submit(listener::activate);
            assertThat(startedA.await(5, TimeUnit.SECONDS)).isTrue();
            var concurrent = executor.submit(() -> listener.accept("B"));
            concurrent.get(5, TimeUnit.SECONDS);
            finishA.countDown();
            activation.get(5, TimeUnit.SECONDS);

            listener.accept("C");
            assertThat(delivered).containsExactly("A", "B", "C");
        } finally {
            executor.shutdownNow();
        }
    }
}
