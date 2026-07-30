package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.upload.BusinessAttachmentTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BusinessScheduleSpringWiringIT {
    @Test
    void production_component_requires_attachment_ticket_service() {
        BusinessAttachmentTicketService tickets = mock(BusinessAttachmentTicketService.class);
        new ApplicationContextRunner()
                .withPropertyValues("babiq.business.enabled=true")
                .withBean(OaWorkbenchGateway.class, () -> mock(OaWorkbenchGateway.class))
                .withBean(BusinessOaSessionRegistry.class, () -> mock(BusinessOaSessionRegistry.class))
                .withBean(OaAuthenticatedRequestExecutor.class, () -> mock(OaAuthenticatedRequestExecutor.class))
                .withBean(BusinessAttachmentTicketService.class, () -> tickets)
                .withBean(BusinessScheduleOperationRepository.class,
                        () -> mock(BusinessScheduleOperationRepository.class))
                .withUserConfiguration(TestConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BusinessScheduleService service = context.getBean(BusinessScheduleService.class);
                    assertThat(ReflectionTestUtils.getField(service, "attachmentTickets")).isSameAs(tickets);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BusinessScheduleService.class)
    static class TestConfiguration {
    }
}
