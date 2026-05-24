package com.wzx.babiq.server.approval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.ApprovalRuleEntity;
import com.wzx.babiq.server.persistence.mapper.ApprovalRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Always 审批规则服务。
 *
 * <p>P2-3 只实现 session scope：用户在某个 thread 里点“始终允许”后，只有同一个 thread、
 * 同一个 tool、同一个参数指纹会自动放行。这样既减少重复审批，又不会扩大到全局永久授权。</p>
 */
@Service
public class ApprovalRuleService {

    /** P2-3 唯一启用的规则 scope。 */
    public static final String SCOPE_SESSION = "session";
    /** 规则决策固定值。 */
    private static final String DECISION_ALWAYS = "always";

    /** ApprovalRule 表 mapper。 */
    private final ApprovalRuleMapper approvalRuleMapper;

    /**
     * 创建 ApprovalRuleService。
     *
     * @param approvalRuleMapper always 规则 mapper
     */
    public ApprovalRuleService(ApprovalRuleMapper approvalRuleMapper) {
        this.approvalRuleMapper = approvalRuleMapper;
    }

    /**
     * 记录一条 session scope 的 always 规则。
     *
     * @param threadId 当前会话 id
     * @param toolName 工具名
     * @param arguments 工具参数 JSON
     * @param scope 请求 scope，P2-3 只允许 session
     */
    @Transactional
    public void rememberAlways(String threadId, String toolName, String arguments, String scope) {
        if (!SCOPE_SESSION.equalsIgnoreCase(scope)) {
            throw new IllegalArgumentException("P2-3 只支持 session scope 的始终允许");
        }
        ApprovalRuleEntity entity = new ApprovalRuleEntity();
        entity.setRuleId("rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        entity.setScope(SCOPE_SESSION);
        entity.setThreadId(requireText(threadId, "threadId"));
        entity.setToolName(requireText(toolName, "toolName"));
        entity.setArgsFingerprint(fingerprint(toolName, arguments));
        entity.setDecision(DECISION_ALWAYS);
        entity.setCreatedAt(Instant.now().toString());
        approvalRuleMapper.insert(entity);
    }

    /**
     * 判断指定工具调用是否命中 always 规则。
     *
     * @param threadId 当前会话 id
     * @param toolName 工具名
     * @param arguments 工具参数 JSON
     * @return true 表示可自动按 approve 恢复
     */
    public boolean isAlwaysAllowed(String threadId, String toolName, String arguments) {
        Long count = approvalRuleMapper.selectCount(Wrappers.<ApprovalRuleEntity>lambdaQuery()
                .eq(ApprovalRuleEntity::getScope, SCOPE_SESSION)
                .eq(ApprovalRuleEntity::getThreadId, threadId)
                .eq(ApprovalRuleEntity::getToolName, toolName)
                .eq(ApprovalRuleEntity::getArgsFingerprint, fingerprint(toolName, arguments))
                .eq(ApprovalRuleEntity::getDecision, DECISION_ALWAYS));
        return count != null && count > 0;
    }

    /**
     * 清理某个 thread 的 session scope 规则。
     *
     * @param threadId 会话 id
     */
    @Transactional
    public void expireSession(String threadId) {
        approvalRuleMapper.delete(Wrappers.<ApprovalRuleEntity>lambdaQuery()
                .eq(ApprovalRuleEntity::getScope, SCOPE_SESSION)
                .eq(ApprovalRuleEntity::getThreadId, threadId));
    }

    private static String fingerprint(String toolName, String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalizedArguments = arguments == null ? "" : arguments.trim();
            byte[] hash = digest.digest((toolName + "\n" + normalizedArguments).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("生成审批参数指纹失败", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + fieldName);
        }
        return value;
    }
}
