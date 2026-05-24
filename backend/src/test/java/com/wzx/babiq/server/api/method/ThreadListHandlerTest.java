package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.ThreadListResult;
import com.wzx.babiq.server.api.dto.ThreadSummaryDto;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * thread/list 协议处理器测试。
 *
 * <p>P2-2 要求 handler 只解析 JSON-RPC 参数，不直接碰 MyBatis Mapper；真正查询委托给
 * ConversationApplicationService。这样未来列表分页或过滤变复杂时，协议层仍然很薄。</p>
 */
class ThreadListHandlerTest {

    /** 测试用 ObjectMapper，模拟 dispatcher 传给 handler 的 JsonNode 参数。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void method_should_be_thread_list() {
        ThreadListHandler handler = new ThreadListHandler(mock(ConversationApplicationService.class));

        assertThat(handler.method()).isEqualTo("thread/list");
    }

    @Test
    void handle_should_use_default_limit_and_hide_archived_threads() throws Exception {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        ThreadListResult expected = new ThreadListResult(List.of(sampleThread()), null);
        when(service.listThreads(eq("E:\\BaBiQ"), eq(false), eq(30), isNull())).thenReturn(expected);
        ThreadListHandler handler = new ThreadListHandler(service);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("cwd", "E:\\BaBiQ")), null);

        assertThat(result).isSameAs(expected);
        verify(service).listThreads("E:\\BaBiQ", false, 30, null);
    }

    @Test
    void handle_should_clip_limit_to_one_hundred() throws Exception {
        ConversationApplicationService service = mock(ConversationApplicationService.class);
        when(service.listThreads(eq("E:\\BaBiQ"), eq(true), eq(100), isNull()))
                .thenReturn(new ThreadListResult(List.of(), null));
        ThreadListHandler handler = new ThreadListHandler(service);

        handler.handle(objectMapper.valueToTree(Map.of(
                "cwd", "E:\\BaBiQ",
                "includeArchived", true,
                "limit", 500)), null);

        verify(service).listThreads("E:\\BaBiQ", true, 100, null);
    }

    private ThreadSummaryDto sampleThread() {
        return new ThreadSummaryDto(
                "thr_1",
                "分析 BaBiQ 项目结构",
                "E:\\BaBiQ",
                "deepseek",
                "deepseek-v4-pro",
                "active",
                "completed",
                "2026-05-24T12:00:00Z",
                3);
    }
}
