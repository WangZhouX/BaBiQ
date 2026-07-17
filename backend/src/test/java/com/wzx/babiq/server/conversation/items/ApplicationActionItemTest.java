package com.wzx.babiq.server.conversation.items;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** applicationAction 是纯展示记录，字段集合不得扩成业务输入或身份容器。 */
class ApplicationActionItemTest {

    @Test
    void exposesOnlyTheApprovedSafeFields() {
        assertThat(ApplicationActionItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "id", "type", "executionId", "actionId", "title", "risk", "status",
                        "previewSummary", "errorCode", "errorSummary", "durationMs");
    }
}
