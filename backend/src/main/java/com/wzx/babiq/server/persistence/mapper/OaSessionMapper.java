package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.OaSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** OA 会话非敏感索引 mapper。 */
@Mapper
public interface OaSessionMapper extends BaseMapper<OaSessionEntity> {

    /** SQLite 的 IS 运算符为 nullable 字段提供 NULL-safe 精确快照匹配。 */
    @Update("""
            UPDATE bq_business_oa_sessions
            SET desktop_instance_id = #{next.desktopInstanceId},
                desktop_session_id = #{next.desktopSessionId},
                user_id = #{next.userId},
                tenant_id = #{next.tenantId},
                platform_id = #{next.platformId},
                phase = #{next.phase},
                generation = #{next.generation},
                active_credential_ref = #{next.activeCredentialRef},
                staged_credential_ref = #{next.stagedCredentialRef},
                credential_version = #{next.credentialVersion},
                install_started_at = #{next.installStartedAt},
                installed_at = #{next.installedAt},
                detached_at = #{next.detachedAt},
                revoked_at = #{next.revokedAt},
                updated_at = #{next.updatedAt},
                installation_id = #{next.installationId},
                installation_owner_desktop_instance_id = #{next.installationOwnerDesktopInstanceId},
                installation_owner_desktop_session_id = #{next.installationOwnerDesktopSessionId},
                installation_target_generation = #{next.installationTargetGeneration},
                installation_expires_at = #{next.installationExpiresAt}
            WHERE auth_session_id = #{expected.authSessionId}
              AND desktop_instance_id IS #{expected.desktopInstanceId}
              AND desktop_session_id IS #{expected.desktopSessionId}
              AND user_id IS #{expected.userId}
              AND tenant_id IS #{expected.tenantId}
              AND platform_id IS #{expected.platformId}
              AND phase IS #{expected.phase}
              AND generation IS #{expected.generation}
              AND active_credential_ref IS #{expected.activeCredentialRef}
              AND staged_credential_ref IS #{expected.stagedCredentialRef}
              AND credential_version IS #{expected.credentialVersion}
              AND install_started_at IS #{expected.installStartedAt}
              AND installed_at IS #{expected.installedAt}
              AND detached_at IS #{expected.detachedAt}
              AND revoked_at IS #{expected.revokedAt}
              AND updated_at IS #{expected.updatedAt}
              AND installation_id IS #{expected.installationId}
              AND installation_owner_desktop_instance_id IS #{expected.installationOwnerDesktopInstanceId}
              AND installation_owner_desktop_session_id IS #{expected.installationOwnerDesktopSessionId}
              AND installation_target_generation IS #{expected.installationTargetGeneration}
              AND installation_expires_at IS #{expected.installationExpiresAt}
            """)
    int compareAndSwapExact(
            @Param("expected") OaSessionEntity expected,
            @Param("next") OaSessionEntity next);
}
