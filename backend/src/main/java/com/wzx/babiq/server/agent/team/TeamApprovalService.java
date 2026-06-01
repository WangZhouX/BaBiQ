package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P6-3 团队协作运行前整体审批服务。
 *
 * <p>该服务只解释审批范围、冻结团队规格和提前校验声明的写入范围；真正的工具执行
 * 仍复用 BaBiQ 现有沙箱拦截器，因此这里不会自行放大权限。</p>
 */
@Service
public class TeamApprovalService {

    /**
     * 根据团队规格生成审批摘要。
     */
    public TeamApprovalScope buildScope(BabiqTeamSpec spec) {
        List<String> members = spec.members().stream().map(BabiqTeamMember::name).toList();
        List<String> tools = spec.members().stream()
                .flatMap(member -> member.toolNames().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        List<String> writeScopes = spec.members().stream()
                .flatMap(member -> member.writeScopes().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        String description = """
                团队：%s
                目标：%s
                成员：%s
                工具：%s
                写入范围：%s
                运行轮数：%d
                沙箱：%s
                """.formatted(
                spec.title(),
                spec.goal(),
                String.join(" -> ", members),
                tools.isEmpty() ? "无" : String.join(", ", tools),
                writeScopes.isEmpty() ? "无" : String.join(", ", writeScopes),
                spec.maxRounds(),
                spec.sandboxMode());
        return new TeamApprovalScope(spec.requiresWriteAccess(), description, members, tools, writeScopes);
    }

    /**
     * 把用户审批结果安装成冻结规格；effectiveSandboxMode 来自 turn 快照，防止团队自行提权。
     */
    public BabiqTeamSpec approveOnce(BabiqTeamSpec spec, SandboxMode effectiveSandboxMode) {
        return spec.approvedAndFrozen(effectiveSandboxMode == null ? spec.sandboxMode() : effectiveSandboxMode);
    }

    /**
     * 在工作区沙箱下提前检查声明的写入范围，给用户更早、更清楚的错误。
     */
    public void validateWriteScopes(BabiqTeamSpec spec, Path cwd) {
        if (spec.sandboxMode() != SandboxMode.WORKSPACE_WRITE || cwd == null) {
            return;
        }
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        Set<String> badScopes = new LinkedHashSet<>();
        for (BabiqTeamMember member : spec.members()) {
            for (String scope : member.writeScopes()) {
                if (scope == null || scope.isBlank()) {
                    continue;
                }
                Path path = Path.of(scope).toAbsolutePath().normalize();
                if (!path.startsWith(normalizedCwd)) {
                    badScopes.add(scope);
                }
            }
        }
        if (!badScopes.isEmpty()) {
            throw new IllegalArgumentException("团队写入范围必须位于当前工作区内: " + badScopes);
        }
    }
}
