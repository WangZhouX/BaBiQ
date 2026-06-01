package com.wzx.babiq.server.agent.delegation;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.interceptor.BaBiQStreamingTokenUsageInterceptor;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.security.Spotlighter;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子 Agent 模型解析策略测试。
 *
 * <p>P6-1 允许子 Agent 默认继承父 Agent 当前 provider，也允许后续通过
 * {@link BabiqAgentSpec.ModelPolicy#provider(String)} 指定 provider。本测试直接运行
 * {@link SubAgentRuntimeFactory#delegate(BabiqAgentSpec, String, ToolContext, SubAgentDelegationContext)}，
 * 确认 provider 选择确实交给 {@link ChatClientFactory} 统一解析。</p>
 */
class SubAgentModelResolutionTest {

    @Test
    void delegate_should_resolve_active_provider_when_model_policy_inherits() {
        ChatClientFactory chatClientFactory = mock(ChatClientFactory.class);
        when(chatClientFactory.resolveChatModel(isNull())).thenReturn(new FinalAnswerChatModel("inherit ok"));
        SubAgentRuntimeFactory factory = runtimeFactory(chatClientFactory);

        String result = factory.delegate(BuiltInSubAgents.explorer(), "只读检查",
                new ToolContext(Map.of()), delegation());

        assertThat(result).contains("inherit ok");
        verify(chatClientFactory).resolveChatModel(isNull());
    }

    @Test
    void delegate_should_resolve_configured_provider_when_model_policy_overrides() {
        ChatClientFactory chatClientFactory = mock(ChatClientFactory.class);
        when(chatClientFactory.resolveChatModel(eq("cheap-readonly"))).thenReturn(new FinalAnswerChatModel("override ok"));
        SubAgentRuntimeFactory factory = runtimeFactory(chatClientFactory);
        BabiqAgentSpec spec = new BabiqAgentSpec(
                "explorer_override",
                "只读探索",
                "Read only exploration",
                "You are a read-only child agent.",
                List.of(),
                BabiqAgentSpec.ModelPolicy.provider("cheap-readonly"),
                BabiqAgentMode.READ_ONLY_TOOL);

        String result = factory.delegate(spec, "只读检查", new ToolContext(Map.of()), delegation());

        assertThat(result).contains("override ok");
        verify(chatClientFactory).resolveChatModel("cheap-readonly");
    }

    private SubAgentRuntimeFactory runtimeFactory(ChatClientFactory chatClientFactory) {
        ToolRegistry registry = new ToolRegistry(List.of());
        ObjectProvider<ToolRegistry> provider = mock();
        when(provider.getObject()).thenReturn(registry);
        AgentLoopProperties properties = new AgentLoopProperties(
                8,
                ApprovalPolicy.NEVER,
                SandboxMode.WORKSPACE_WRITE,
                List.of(),
                new AgentLoopProperties.Tools(new AgentLoopProperties.Output(4000)));
        return new SubAgentRuntimeFactory(
                chatClientFactory,
                provider,
                properties,
                new BaBiQSandboxInterceptor(properties, new ConversationService()),
                new ToolObservationInterceptor(new BaBiQMetrics()),
                new SpotlightingToolInterceptor(new Spotlighter()),
                new BaBiQTokenUsageHook(),
                new BaBiQStreamingTokenUsageInterceptor());
    }

    private SubAgentDelegationContext delegation() {
        return SubAgentDelegationContext.started(
                "it_model_resolution",
                "dlg_model_resolution",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                BuiltInSubAgents.EXPLORER_NAME,
                BabiqAgentMode.READ_ONLY_TOOL,
                null,
                null);
    }

    /**
     * 固定返回文本的 ChatModel，专门用于验证子 Agent provider 解析，不引入真实网络调用。
     */
    private record FinalAnswerChatModel(String text) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
