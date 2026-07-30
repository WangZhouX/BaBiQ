package com.wzx.babiq.server.business.oa.client;

import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import java.util.List;

public interface OaAuthenticationGateway {
    List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account);
    OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password);
    OaAuthDtos.OaCredential refresh(String tenantId, char[] refreshToken);
    OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] accessToken);
    void logout(String tenantId, char[] accessToken);
}
