package com.wzx.babiq.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMetadataTest {

    @Test
    @DisplayName("qwen-plus 返回 1_000_000")
    void qwen_plus_should_return_one_million_context_window() {
        assertThat(ModelMetadata.contextWindowOf("qwen-plus")).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("deepseek-chat 返回 128_000")
    void deepseek_chat_should_return_128k_context_window() {
        assertThat(ModelMetadata.contextWindowOf("deepseek-chat")).isEqualTo(128_000);
    }

    @Test
    @DisplayName("llama3:8b 返回 8_192")
    void llama3_8b_should_return_8k_context_window() {
        assertThat(ModelMetadata.contextWindowOf("llama3:8b")).isEqualTo(8_192);
    }

    @Test
    @DisplayName("Claude 4.6/4.8 官方模型返回已核对的上下文窗口")
    void claude_current_models_should_return_verified_context_windows() {
        assertThat(ModelMetadata.contextWindowOf("claude-sonnet-4-6")).isEqualTo(1_000_000);
        assertThat(ModelMetadata.contextWindowOf("claude-opus-4-8")).isEqualTo(1_000_000);
        assertThat(ModelMetadata.contextWindowOf("claude-haiku-4-5")).isEqualTo(200_000);
    }

    @Test
    @DisplayName("模型名大小写不敏感")
    void model_name_should_be_case_insensitive() {
        assertThat(ModelMetadata.contextWindowOf("QWEN-PLUS")).isEqualTo(1_000_000);
        assertThat(ModelMetadata.contextWindowOf("Qwen-Plus")).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("未知模型回退到默认 32_768")
    void unknown_model_should_return_default_context_window() {
        assertThat(ModelMetadata.contextWindowOf("my-proprietary-llm"))
                .isEqualTo(ModelMetadata.DEFAULT_CONTEXT_WINDOW)
                .isEqualTo(32_768);
    }

    @Test
    @DisplayName("null 或空白模型名拒绝")
    void null_or_blank_model_should_throw_clear_exception() {
        assertThatThrownBy(() -> ModelMetadata.contextWindowOf(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelMetadata.contextWindowOf(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelMetadata.contextWindowOf("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("内置主流模型覆盖率至少包含关键模型")
    void builtin_mapping_should_cover_required_models() {
        String[] requiredModels = {
                "qwen-plus",
                "qwen-turbo",
                "qwen-max",
                "qwq-plus",
                "deepseek-chat",
                "gpt-4o",
                "claude-opus-4-8",
                "claude-sonnet-4-6",
                "claude-haiku-4-5",
                "llama3:8b"
        };

        for (String model : requiredModels) {
            assertThat(ModelMetadata.contextWindowOf(model))
                    .as("model %s should be builtin", model)
                    .isNotEqualTo(ModelMetadata.DEFAULT_CONTEXT_WINDOW);
        }
    }
}
