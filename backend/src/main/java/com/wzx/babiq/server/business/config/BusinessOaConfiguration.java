package com.wzx.babiq.server.business.config;

import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.client.RestClientOaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.RestClientOaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.BusinessOaSecretCleanupRepository;
import com.wzx.babiq.server.business.oa.session.BusinessOaSecretCleanupService;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.OaSessionPersistenceService;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import com.wzx.babiq.server.business.oa.session.OaTokenRefreshCoordinator;
import com.wzx.babiq.server.business.oa.session.OaSessionTerminalizer;
import com.wzx.babiq.server.business.workbench.BusinessDataScopeValidator;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import com.wzx.babiq.server.business.upload.BusinessAttachmentTicketService;
import com.wzx.babiq.server.business.upload.BusinessAttachmentFileIdStore;
import com.wzx.babiq.server.business.upload.BusinessAttachmentRemoteUploader;
import com.wzx.babiq.server.business.upload.RestClientBusinessAttachmentRemoteUploader;
import com.wzx.babiq.server.settings.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring wiring for the server-owned OA boundary used by the business profile. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class BusinessOaConfiguration {

    @Bean
    @ConditionalOnMissingBean(OaAuthenticationGateway.class)
    OaAuthenticationGateway oaAuthenticationGateway(BusinessOaProperties properties) {
        return new RestClientOaAuthenticationGateway(properties);
    }

    @Bean
    @ConditionalOnMissingBean(OaWorkbenchGateway.class)
    OaWorkbenchGateway oaWorkbenchGateway(BusinessOaProperties properties) {
        return new RestClientOaWorkbenchGateway(properties);
    }

    @Bean
    @ConditionalOnMissingBean(OaSessionCredentialStore.class)
    OaSessionCredentialStore oaSessionCredentialStore(SecretStore secretStore) {
        return new OaSessionCredentialStore(secretStore);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessAttachmentFileIdStore.class)
    BusinessAttachmentFileIdStore businessAttachmentFileIdStore(SecretStore secretStore) {
        return new BusinessAttachmentFileIdStore(secretStore);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessAttachmentRemoteUploader.class)
    BusinessAttachmentRemoteUploader businessAttachmentRemoteUploader(
            BusinessOaProperties properties,
            OaAuthenticatedRequestExecutor executor) {
        return new RestClientBusinessAttachmentRemoteUploader(properties, executor);
    }

    @Bean
    @ConditionalOnMissingBean(OaSessionPersistenceService.class)
    OaSessionPersistenceService oaSessionPersistenceService(OaSessionRepository repository,
                                                             BusinessOaSecretCleanupRepository cleanupRepository,
                                                             BusinessOaSecretCleanupService cleanupService,
                                                             PlatformTransactionManager transactionManager) {
        return new OaSessionPersistenceService(
                repository, cleanupRepository, cleanupService, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(OaTokenRefreshCoordinator.class)
    OaTokenRefreshCoordinator oaTokenRefreshCoordinator(BusinessOaSessionRegistry sessions,
                                                        OaSessionRepository repository,
                                                        OaSessionPersistenceService persistence,
                                                        OaSessionCredentialStore credentials,
                                                        OaAuthenticationGateway gateway) {
        return new OaTokenRefreshCoordinator(sessions, repository, persistence, credentials, gateway);
    }

    @Bean
    @ConditionalOnMissingBean(OaAuthenticatedRequestExecutor.class)
    OaAuthenticatedRequestExecutor oaAuthenticatedRequestExecutor(BusinessOaSessionRegistry sessions,
                                                                   OaSessionCredentialStore credentials,
                                                                   OaTokenRefreshCoordinator refreshCoordinator,
                                                                   OaSessionTerminalizer terminalizer) {
        return new OaAuthenticatedRequestExecutor(sessions, credentials, refreshCoordinator, terminalizer);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessDataScopeValidator.class)
    BusinessDataScopeValidator businessDataScopeValidator() {
        return new BusinessDataScopeValidator();
    }

    @Bean
    @ConditionalOnMissingBean(BusinessScheduleService.class)
    BusinessScheduleService businessScheduleService(OaWorkbenchGateway gateway,
                                                     BusinessOaSessionRegistry sessions,
                                                     OaAuthenticatedRequestExecutor executor,
                                                     BusinessAttachmentTicketService attachmentTickets) {
        return new BusinessScheduleService(gateway, sessions, executor, attachmentTickets);
    }
}
