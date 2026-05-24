package com.wzx.babiq.server.settings;

import org.springframework.stereotype.Service;

/**
 * 审批策略设置薄服务。
 *
 * <p>审批策略最终仍写入 AppSettingsService；单独建这个服务是为了让审批相关 handler
 * 不需要知道其他设置字段。</p>
 */
@Service
public class ApprovalPolicyService {

    /** 应用设置服务，负责持久化和枚举校验。 */
    private final AppSettingsService appSettingsService;

    /**
     * 创建审批策略设置服务。
     *
     * @param appSettingsService 应用设置服务
     */
    public ApprovalPolicyService(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    /**
     * 更新默认审批策略。
     *
     * @param approvalPolicy ApprovalPolicy 枚举名
     * @return 更新后的应用设置
     */
    public AppSettings setPolicy(String approvalPolicy) {
        return appSettingsService.update(new AppSettingsService.AppSettingsUpdate(null, null, approvalPolicy, null));
    }
}
