package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.ThreadArchiveResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * thread/archive 协议处理器测试。
 *
 * <p>归档是软删除：默认最近列表隐藏，但数据库里的 item 和 turn 不能被物理删除。</p>
 */
class ThreadArchiveHandlerTest {

    /** 测试用 JSON 工具，用来模拟 JSON-RPC params。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void archive_should_delegate_to_application_service() throws Exception {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        ThreadArchiveResult expected = new ThreadArchiveResult(true, "thr_1", true);
        when(service.archiveThread("thr_1")).thenReturn(expected);
        ThreadArchiveHandler handler = new ThreadArchiveHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("threadId", "thr_1")), null);

        assertThat(result).isSameAs(expected);
        verify(service).archiveThread("thr_1");
    }

    @Test
    void missing_thread_id_should_throw_invalid_params() {
        ThreadArchiveHandler handler = new ThreadArchiveHandler(mock(ConversationApplicationService.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    @Test
    void running_thread_should_not_be_archived() {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        when(service.archiveThread("thr_running"))
                .thenThrow(new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR, "当前 turn 仍在运行，不能归档"));
        ThreadArchiveHandler handler = new ThreadArchiveHandler(service);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of("threadId", "thr_running")), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.SERVER_ERROR));
    }
}
