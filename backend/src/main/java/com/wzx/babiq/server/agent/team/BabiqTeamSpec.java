package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.sandbox.SandboxMode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一次团队协作的冻结规格。
 *
 * <p>团队协作会把 supervisor 和多个 teammate 放进同一个 Spring AI Alibaba
 * StateGraph。审批通过后，团队成员、工具白名单、写入范围和沙箱模式都必须固定，
 * 防止运行中改变权限边界。</p>
 *
 * @param teamId 团队 id，贯穿协议 item、运行记录和消息时间线
 * @param title 用户可读标题
 * @param goal 团队整体目标
 * @param members 成员列表，构造时按 order 排序并去重校验
 * @param maxRounds supervisor 最多调度轮数，防止循环
 * @param approved 是否已经获得运行前整体审批
 * @param frozen 是否冻结结构；冻结后不能替换成员列表
 * @param sandboxMode 本轮沙箱快照，不允许团队自行提升
 */
public record BabiqTeamSpec(
        String teamId,
        String title,
        String goal,
        List<BabiqTeamMember> members,
        int maxRounds,
        boolean approved,
        boolean frozen,
        SandboxMode sandboxMode
) {

    /**
     * 规范化团队规格，确保后续装配 StateGraph 时拿到稳定顺序和不可变列表。
     */
    public BabiqTeamSpec {
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("团队 id 不能为空");
        }
        title = title == null || title.isBlank() ? teamId : title;
        goal = goal == null || goal.isBlank() ? title : goal;
        members = members == null ? List.of() : members.stream()
                .sorted(Comparator.comparingInt(BabiqTeamMember::order))
                .toList();
        if (members.isEmpty()) {
            throw new IllegalArgumentException("团队至少需要一个成员");
        }
        assertUnique(members);
        maxRounds = maxRounds <= 0 ? 4 : Math.min(maxRounds, 12);
        sandboxMode = sandboxMode == null ? SandboxMode.READ_ONLY : sandboxMode;
    }

    /**
     * 判断整个团队是否包含写类成员。
     */
    public boolean requiresWriteAccess() {
        return members.stream().anyMatch(BabiqTeamMember::requiresWriteAccess);
    }

    /**
     * 按成员技术名或 id 查找成员。
     */
    public Optional<BabiqTeamMember> member(String nameOrId) {
        return members.stream()
                .filter(member -> member.name().equals(nameOrId) || member.memberId().equals(nameOrId))
                .findFirst();
    }

    /**
     * 返回替换成员后的新规格；已冻结团队禁止修改。
     */
    public BabiqTeamSpec withMembers(List<BabiqTeamMember> replacement) {
        if (frozen) {
            throw new IllegalStateException("团队已冻结，不能修改成员");
        }
        return new BabiqTeamSpec(teamId, title, goal, replacement, maxRounds, approved, false, sandboxMode);
    }

    /**
     * 返回审批通过且冻结后的规格，沙箱模式来自 turn 快照而不是成员自行决定。
     */
    public BabiqTeamSpec approvedAndFrozen(SandboxMode effectiveSandboxMode) {
        return new BabiqTeamSpec(teamId, title, goal, members, maxRounds, true, true,
                effectiveSandboxMode == null ? sandboxMode : effectiveSandboxMode);
    }

    private static void assertUnique(List<BabiqTeamMember> members) {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (BabiqTeamMember member : members) {
            if (!ids.add(member.memberId()) || !names.add(member.name())) {
                throw new IllegalArgumentException("团队成员 id/name 不能重复");
            }
        }
    }
}
