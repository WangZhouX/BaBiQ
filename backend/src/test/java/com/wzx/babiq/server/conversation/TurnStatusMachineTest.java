package com.wzx.babiq.server.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnStatusMachineTest {

    @Test
    void created_to_running_is_allowed() {
        Turn turn = new Turn("turn_001", "thr_001");

        turn.start();

        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
    }

    @Test
    void running_to_waiting_approval_and_back_is_allowed() {
        Turn turn = new Turn("turn_001", "thr_001");

        turn.start();
        turn.waitApproval();
        turn.resume();

        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
    }

    @Test
    void running_to_completed_is_allowed() {
        Turn turn = new Turn("turn_001", "thr_001");

        turn.start();
        turn.complete();

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void running_to_failed_records_failure_reason() {
        Turn turn = new Turn("turn_001", "thr_001");

        turn.start();
        turn.fail("模型调用失败");

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).isEqualTo("模型调用失败");
    }

    @Test
    void running_and_waiting_approval_can_be_canceled() {
        Turn runningTurn = new Turn("turn_running", "thr_001");
        Turn waitingTurn = new Turn("turn_waiting", "thr_001");

        runningTurn.start();
        runningTurn.cancel();
        waitingTurn.start();
        waitingTurn.waitApproval();
        waitingTurn.cancel();

        assertThat(runningTurn.status()).isEqualTo(TurnStatus.CANCELED);
        assertThat(waitingTurn.status()).isEqualTo(TurnStatus.CANCELED);
    }

    @Test
    void created_and_waiting_approval_can_expire_when_business_identity_changes() {
        Turn created = new Turn("turn_created", "thr_001");
        Turn waiting = new Turn("turn_waiting", "thr_001");
        waiting.start();
        waiting.waitApproval();

        created.expire("business identity changed");
        waiting.expire("business identity changed");

        assertThat(created.status()).isEqualTo(TurnStatus.EXPIRED);
        assertThat(waiting.status()).isEqualTo(TurnStatus.EXPIRED);
        assertThat(created.failureReason()).isEqualTo("business identity changed");
    }

    @Test
    void terminal_states_reject_further_transitions() {
        Turn turn = new Turn("turn_001", "thr_001");

        turn.start();
        turn.complete();

        assertThatThrownBy(turn::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turnId=turn_001");
        assertThatThrownBy(() -> turn.fail("再次失败"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void cannot_start_twice() {
        Turn turn = new Turn("turn_001", "thr_001");

        turn.start();

        assertThatThrownBy(turn::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }
}
