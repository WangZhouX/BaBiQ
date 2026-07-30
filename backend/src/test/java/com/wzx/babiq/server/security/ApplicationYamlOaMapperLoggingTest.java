package com.wzx.babiq.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlOaMapperLoggingTest {

    private static final String OA_SESSION_MAPPER =
            "logging.level.com.wzx.babiq.server.persistence.mapper.OaSessionMapper";
    private static final String CLEANUP_MAPPER =
            "logging.level.com.wzx.babiq.server.persistence.mapper.BusinessOaSecretCleanupMapper";
    private static final String PROVIDER_MAPPER =
            "logging.level.com.wzx.babiq.server.persistence.mapper.ProviderConfigMapper";

    @Test
    void base_config_disables_sql_value_logging_for_oa_secret_reference_mappers() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertSensitiveMappersAreOff(properties::getProperty);
    }

    @Test
    void business_desktop_profile_merge_keeps_secret_reference_mappers_off() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=business-desktop")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertSensitiveMappersAreOff(context.getEnvironment()::getProperty);
                });
    }

    private static void assertSensitiveMappersAreOff(
            java.util.function.Function<String, String> propertyLookup) {
        assertThat(propertyLookup.apply(OA_SESSION_MAPPER)).isEqualTo("OFF");
        assertThat(propertyLookup.apply(CLEANUP_MAPPER)).isEqualTo("OFF");
        assertThat(propertyLookup.apply(PROVIDER_MAPPER)).isEqualTo("OFF");
    }
}
