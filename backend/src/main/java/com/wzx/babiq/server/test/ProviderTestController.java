package com.wzx.babiq.server.test;

import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.model.ModelMetadata;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.test.dto.ChatRequest;
import com.wzx.babiq.server.test.dto.ChatResponse;
import com.wzx.babiq.server.test.dto.ProviderInfo;
import jakarta.validation.Valid;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P1-2 临时 REST 验证端点。
 *
 * <p>这些接口用于人工验证 provider 列表、真实模型调用和短期记忆。它们不属于最终
 * Agent 协议入口,后续 P1-3 接入 WebSocket/JSON-RPC 后可以迁移到 dev profile
 * 或直接移除。</p>
 */
@RestController
@RequestMapping("/api/test")
public class ProviderTestController {

    private static final String DEFAULT_THREAD_ID = "default";

    private final ModelProviderRegistry registry;
    private final ChatClientFactory chatClientFactory;

    /**
     * 创建临时 Provider 测试控制器。
     *
     * @param registry provider 注册中心
     * @param chatClientFactory ChatClient 统一工厂
     */
    public ProviderTestController(ModelProviderRegistry registry, ChatClientFactory chatClientFactory) {
        this.registry = registry;
        this.chatClientFactory = chatClientFactory;
    }

    /**
     * 列出所有 provider。
     *
     * @return provider 列表,不包含 api-key
     */
    @GetMapping("/providers")
    public List<ProviderInfo> listProviders() {
        String activeProviderId = registry.active().id();
        return registry.list().stream()
                .map(providerConfig -> ProviderInfo.from(
                        providerConfig,
                        providerConfig.id().equals(activeProviderId),
                        contextWindowOf(providerConfig)))
                .toList();
    }

    /**
     * 调用指定 provider 完成一次聊天。
     *
     * @param providerId 可选 provider id,为空时使用 active-provider
     * @param request 聊天请求
     * @return 模型回复
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestParam(required = false) String providerId,
                             @RequestBody @Valid ChatRequest request) {
        String resolvedProviderId = resolveProviderId(providerId);
        String resolvedThreadId = resolveThreadId(request.threadId());
        ModelProviderConfig providerConfig = registry.get(resolvedProviderId);

        String reply = chatClientFactory.resolve(resolvedProviderId)
                .prompt()
                .user(request.text())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, resolvedThreadId))
                .call()
                .content();

        return new ChatResponse(
                resolvedProviderId,
                providerConfig.model(),
                resolvedThreadId,
                reply
        );
    }

    private String resolveProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return registry.active().id();
        }
        return providerId;
    }

    private static String resolveThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return DEFAULT_THREAD_ID;
        }
        return threadId;
    }

    private static int contextWindowOf(ModelProviderConfig providerConfig) {
        if (providerConfig.contextWindow() != null) {
            return providerConfig.contextWindow();
        }
        return ModelMetadata.contextWindowOf(providerConfig.model());
    }
}
