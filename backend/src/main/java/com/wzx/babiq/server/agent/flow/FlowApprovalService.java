package com.wzx.babiq.server.agent.flow;

import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P6-2 运行前整体审批服务。
 *
 * <p>该服务只做“审批范围解释”和“冻结规格”两件事；它不执行工具，也不改变沙箱。
 * 真正的写入边界仍由 {@code BaBiQSandboxInterceptor} 在工具调用前逐次校验。</p>
 */
@Service
public class FlowApprovalService {

    /**
     * 根据流程规格生成审批摘要。
     */
    public FlowApprovalScope buildScope(BabiqFlowSpec spec) {
        List<String> nodes = spec.nodes().stream().map(BabiqFlowNode::name).toList();
        List<String> tools = spec.nodes().stream()
                .flatMap(node -> node.toolNames().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        List<String> writeScopes = spec.nodes().stream()
                .flatMap(node -> node.writeScopes().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        String description = """
                流程：%s
                拓扑：%s
                节点：%s
                工具：%s
                写入范围：%s
                沙箱：%s
                """.formatted(
                spec.title(),
                spec.topology(),
                String.join(" -> ", nodes),
                tools.isEmpty() ? "无" : String.join(", ", tools),
                writeScopes.isEmpty() ? "无" : String.join(", ", writeScopes),
                spec.sandboxMode());
        return new FlowApprovalScope(spec.requiresWriteAccess(), description, nodes, tools, writeScopes);
    }

    /**
     * 把用户审批结果安装成冻结规格；effectiveSandboxMode 来自 turn 快照，防止流程自行提权。
     */
    public BabiqFlowSpec approveOnce(BabiqFlowSpec spec, SandboxMode effectiveSandboxMode) {
        return spec.approvedAndFrozen(effectiveSandboxMode == null ? spec.sandboxMode() : effectiveSandboxMode);
    }

    /**
     * 在工作区沙箱下提前检查声明的写入范围，给用户更早、更清楚的错误。
     */
    public void validateWriteScopes(BabiqFlowSpec spec, Path cwd) {
        if (spec.sandboxMode() != SandboxMode.WORKSPACE_WRITE || cwd == null) {
            return;
        }
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        Set<String> badScopes = new LinkedHashSet<>();
        for (BabiqFlowNode node : spec.nodes()) {
            for (String scope : node.writeScopes()) {
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
            throw new IllegalArgumentException("流程写入范围必须位于当前工作区内: " + badScopes);
        }
    }
}
