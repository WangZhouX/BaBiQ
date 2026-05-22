package com.wzx.babiq.server.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * approval/request 载荷契约测试。
 *
 * <p>这里锁定 record 组件的必填约束，避免后续只补通知发送逻辑，却把字段契约改松。
 * D22 要求这类载荷字段都应保持 required=true。</p>
 */
class ApprovalRequestPayloadTest {

    @Test
    void every_record_component_should_be_required() throws NoSuchFieldException {
        for (RecordComponent component : ApprovalRequestPayload.class.getRecordComponents()) {
            JsonProperty jsonProperty = ApprovalRequestPayload.class
                    .getDeclaredField(component.getName())
                    .getAnnotation(JsonProperty.class);
            assertThat(jsonProperty)
                    .as("字段 %s 必须标记 required=true", component.getName())
                    .isNotNull();
            assertThat(jsonProperty.required())
                    .as("字段 %s 必须标记 required=true", component.getName())
                    .isTrue();
        }
    }
}
