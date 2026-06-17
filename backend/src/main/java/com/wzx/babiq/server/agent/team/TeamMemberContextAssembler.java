package com.wzx.babiq.server.agent.team;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 团队成员单轮上下文装配器。
 *
 * <p>成员无状态、每轮重建上下文。这里只 push 有界的四块信息，成员如需全文，
 * 通过 `team.md` 索引里的 `rounds/*.md` 引用自行 read_file。</p>
 */
@Component
public class TeamMemberContextAssembler {

    /** 团队记忆工作区。 */
    private final TeamMemoryWorkspace memoryWorkspace;

    /**
     * 创建成员上下文装配器。
     */
    public TeamMemberContextAssembler(TeamMemoryWorkspace memoryWorkspace) {
        this.memoryWorkspace = memoryWorkspace;
    }

    /**
     * 装配某成员本轮执行指令。
     */
    public String assembleMemberInstruction(BabiqTeamSpec spec,
                                            BabiqTeamMember member,
                                            int round,
                                            String supervisorReason,
                                            List<TeamMessageRecord> injected) {
        String directMessages = injected == null || injected.isEmpty()
                ? "无"
                : injected.stream()
                .map(message -> "- " + message.content())
                .collect(Collectors.joining("\n"));
        return """
                ## 团队目标
                %s

                ## 本职任务
                成员：%s
                职能：%s
                当前轮次：%d
                supervisor 理由：%s

                ## 用户给本成员的轮次间消息
                %s

                ## 滚动讨论概要
                %s

                ## team.md 索引
                %s
                """.formatted(
                spec.goal(),
                member.displayName(),
                member.task(),
                Math.max(0, round),
                blankToDefault(supervisorReason, "无"),
                directMessages,
                blankToDefault(memoryWorkspace.readDigest(spec.teamId()), "暂无"),
                blankToDefault(memoryWorkspace.readIndex(spec.teamId()), "暂无")).trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
