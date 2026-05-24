package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.ThreadLoadResult;
import com.wzx.babiq.server.api.dto.ThreadMetaDto;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * thread/load 协议处理器测试。
 *
 * <p>历史恢复必须原样返回 item payload JSON，避免桌面端加载历史时和实时 WebSocket item 走两套协议。</p>
 */
class ThreadLoadHandlerTest {

    /** 测试用 JSON 工具，既构造 params，也构造历史 item payload。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missing_thread_id_should_throw_invalid_params() {
        ThreadLoadHandler handler = new ThreadLoadHandler(mock(ConversationApplicationService.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    @Test
    void handle_should_return_items_in_sequence_order() throws Exception {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        JsonNode user = objectMapper.readTree("{\"id\":\"it_1\",\"type\":\"userMessage\",\"text\":\"你好\"}");
        JsonNode agent = objectMapper.readTree("{\"id\":\"it_2\",\"type\":\"agentMessage\",\"text\":\"你好，有什么可以帮忙？\"}");
        ThreadLoadResult expected = new ThreadLoadResult(
                new ThreadMetaDto("thr_1", "新对话", "E:\\BaBiQ", "active"),
                List.of(user, agent),
                null,
                null);
        when(service.loadThread("thr_1", 200, null)).thenReturn(expected);
        ThreadLoadHandler handler = new ThreadLoadHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("threadId", "thr_1")), null);

        assertThat(result).isSameAs(expected);
        verify(service).loadThread("thr_1", 200, null);
    }

    @Test
    void service_not_found_error_should_keep_json_rpc_error_code() {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        when(service.loadThread("thr_missing", 200, null))
                .thenThrow(new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "thread 不存在: thr_missing"));
        ThreadLoadHandler handler = new ThreadLoadHandler(service);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of("threadId", "thr_missing")), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
