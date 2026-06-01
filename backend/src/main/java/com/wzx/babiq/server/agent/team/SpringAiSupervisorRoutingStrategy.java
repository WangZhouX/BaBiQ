package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.model.ChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Spring AI structured output 的 supervisor 路由策略。
 *
 * <p>BaBiQ 不自研模型调用协议；这里复用当前 active provider 的 {@link ChatClient}，
 * 让模型输出 {@link SupervisorRouteDecision}。如果 provider 不可用或结构化输出失败，
 * 上层服务会自动回退到确定性路由。</p>
 */
@Component
public class SpringAiSupervisorRoutingStrategy implements SupervisorRoutingStrategy {

    /** ChatClient 工厂，跟随当前 active provider。 */
    private final ChatClientFactory chatClientFactory;

    /**
     * 创建 Spring AI 路由策略。
     */
    public SpringAiSupervisorRoutingStrategy(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public SupervisorRouteDecision decide(BabiqTeamSpec spec, List<TeamMessageRecord> timeline, int round) {
        ChatClient client = chatClientFactory.active();
        return client.prompt()
                .system("""
                        你是 BaBiQ 团队协作 supervisor。
                        只能把 next 设置为给定成员名之一或 FINISH。
                        当信息足够或已达到目标时选择 FINISH。
                        不要发明成员名，不要要求未审批工具。
                        """)
                .user("""
                        团队目标：%s
                        当前轮数：%d/%d
                        成员：%s
                        时间线：
                        %s
                        """.formatted(
                        spec.goal(),
                        round,
                        spec.maxRounds(),
                        spec.members().stream().map(BabiqTeamMember::name).toList(),
                        timeline.stream()
                                .map(message -> message.fromAgent() + " -> " + message.toAgent()
                                        + " [" + message.messageType() + "]: " + message.content())
                                .reduce("", (left, right) -> left + "\n" + right)))
                .call()
                .entity(SupervisorRouteDecision.class);
    }
}
