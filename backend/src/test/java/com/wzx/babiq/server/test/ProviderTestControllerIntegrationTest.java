package com.wzx.babiq.server.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.test.dto.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-2 临时 REST 验证端点的集成测试。
 *
 * <p>测试不调用真实模型,只验证 provider 列表、上下文窗口展示和错误响应格式。
 * 真模型与跨轮记忆烟测在 Task 9 单独执行。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderTestControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /api/test/providers 返回四个 provider 且 active 唯一")
    void list_providers_should_return_all_configured_providers() throws Exception {
        mockMvc.perform(get("/api/test/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].id", hasItems(
                        "dashscope-default",
                        "deepseek-official",
                        "oneapi-relay",
                        "claude-oauth",
                        "ollama-local"
                )))
                .andExpect(jsonPath("$[0].id").value("dashscope-default"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].contextWindow").value(1_000_000))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    @Test
    @DisplayName("POST /api/test/chat 调用缺 key provider 时返回 400 业务错误")
    void chat_should_return_bad_request_when_provider_api_key_is_missing() throws Exception {
        ChatRequest request = new ChatRequest("你好", "missing-key-test");

        mockMvc.perform(post("/api/test/chat")
                        .param("providerId", "dashscope-default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_configuration"))
                .andExpect(jsonPath("$.message").value(containsString("dashscope-default")))
                .andExpect(jsonPath("$.message").value(containsString("api-key")));
    }
}
