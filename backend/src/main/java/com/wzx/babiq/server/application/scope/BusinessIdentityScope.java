package com.wzx.babiq.server.application.scope;

/**
 * Thread 创建时冻结的业务身份值。
 *
 * <p>普通模式使用显式 {@link #UNSCOPED}，业务模式则必须携带完整七元身份。
 * 该记录不保存角色和权限：后续异步执行只需要稳定的归属边界，不应再读取可变的当前登录态。</p>
 */
public record BusinessIdentityScope(
        boolean scoped,
        String desktopInstanceId,
        String desktopSessionId,
        String authSessionId,
        long identityEpoch,
        String userId,
        String tenantId,
        String platformId
) {

    /** Spring AI ToolContext / RunnableConfig 中的显式业务身份键。 */
    public static final String METADATA_KEY = "babiq.businessIdentityScope";

    /** 普通 BaBiQ profile 的显式无业务身份标记。 */
    public static final BusinessIdentityScope UNSCOPED = new BusinessIdentityScope(
            false, null, null, null, 0, null, null, null);

    public BusinessIdentityScope {
        if (!scoped) {
            requireUnscoped(desktopInstanceId, desktopSessionId, authSessionId, identityEpoch,
                    userId, tenantId, platformId);
        } else {
            requireText(desktopInstanceId, "desktopInstanceId");
            requireText(desktopSessionId, "desktopSessionId");
            requireText(authSessionId, "authSessionId");
            requireText(userId, "userId");
            requireText(tenantId, "tenantId");
            requireText(platformId, "platformId");
            if (identityEpoch <= 0) {
                throw new IllegalArgumentException("identityEpoch must be positive");
            }
        }
    }

    /** 创建完整业务身份作用域，避免调用点自行组合不完整记录。 */
    public static BusinessIdentityScope scoped(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long identityEpoch,
            String userId,
            String tenantId,
            String platformId) {
        return new BusinessIdentityScope(true, desktopInstanceId, desktopSessionId, authSessionId,
                identityEpoch, userId, tenantId, platformId);
    }

    @Override
    public String toString() {
        if (!scoped) {
            return "BusinessIdentityScope(UNSCOPED)";
        }
        return "BusinessIdentityScope(scoped=true, desktopInstanceId=[REDACTED], "
                + "desktopSessionId=[REDACTED], authSessionId=[REDACTED], identityEpoch="
                + identityEpoch + ", userId=[REDACTED], tenantId=[REDACTED], platformId=[REDACTED])";
    }

    private static void requireUnscoped(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long identityEpoch,
            String userId,
            String tenantId,
            String platformId) {
        if (desktopInstanceId != null || desktopSessionId != null || authSessionId != null
                || identityEpoch != 0 || userId != null || tenantId != null || platformId != null) {
            throw new IllegalArgumentException("unscoped identity must not contain business identifiers");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
