package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent Loop 配置快照。
 *
 * <p>该 record 绑定 {@code babiq.agent.*}，由 ReActStrategy、沙箱拦截器和执行器读取。
 * 它存在的原因是把 D21 迭代上限、D23 审批策略和 D31 沙箱模式集中成不可变配置，
 * 避免在业务流程里散落硬编码。协议 handler、AgentLoop 与 Interceptor 都会间接使用它。</p>
 *
 * @param maxIterations 单个 turn 允许的最大模型调用次数
 * @param approvalPolicy 审批策略，默认 ON_REQUEST
 * @param sandboxMode 沙箱模式，默认 WORKSPACE_WRITE
 * @param writableRoots 额外可写根目录，默认使用 thread.cwd
 * @param tools 工具相关配置
 */
@ConfigurationProperties(prefix = "babiq.agent")
public record AgentLoopProperties(
        int maxIterations,
        ApprovalPolicy approvalPolicy,
        SandboxMode sandboxMode,
        List<Path> writableRoots,
        Tools tools
) {

    /**
     * 补齐配置默认值，避免缺省 yml 让 Agent Loop 无法启动。
     *
     * @param maxIterations 原始迭代上限
     * @param approvalPolicy 原始审批策略
     * @param sandboxMode 原始沙箱模式
     * @param writableRoots 原始可写根目录列表
     * @param tools 原始工具配置
     */
    public AgentLoopProperties {
        if (maxIterations <= 0) {
            maxIterations = 20;
        }
        if (approvalPolicy == null) {
            approvalPolicy = ApprovalPolicy.ON_REQUEST;
        }
        if (sandboxMode == null) {
            sandboxMode = SandboxMode.WORKSPACE_WRITE;
        }
        writableRoots = writableRoots == null ? List.of() : List.copyOf(writableRoots);
        if (tools == null) {
            tools = new Tools(new Output(4000));
        }
    }

    /**
     * 工具配置分组。
     *
     * @param output 工具输出截断配置
     */
    public record Tools(Output output) {

        /**
         * 补齐工具配置默认值。
         *
         * @param output 原始输出配置
         */
        public Tools {
            if (output == null) {
                output = new Output(4000);
            }
        }
    }

    /**
     * 工具输出截断配置。
     *
     * @param maxTokens SAA LargeResultEvictionInterceptor 的全局 token 阈值
     */
    public record Output(int maxTokens) {

        /**
         * 补齐 token 阈值默认值。
         *
         * @param maxTokens 原始 token 阈值
         */
        public Output {
            if (maxTokens <= 0) {
                maxTokens = 4000;
            }
        }
    }
}
