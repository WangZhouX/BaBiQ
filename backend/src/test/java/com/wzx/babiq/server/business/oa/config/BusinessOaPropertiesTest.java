package com.wzx.babiq.server.business.oa.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessOaPropertiesTest {

    @Test
    void buildsTheLawApiEndpointAndAcceptsTheConfiguredPrivateHttpEndpoint() {
        BusinessOaProperties properties = new BusinessOaProperties(
                "http://192.168.1.20:48080", "/law-api", 2, 30_000, true);

        assertThat(properties.endpointBase()).isEqualTo("http://192.168.1.20:48080/law-api");
        assertThat(properties.platformId()).isEqualTo(2);
        assertThat(properties.requestTimeoutMs()).isEqualTo(30_000);
    }

    @Test
    void rejectsUnsafeOrMalformedConfiguration() {
        assertThatThrownBy(() -> new BusinessOaProperties(
                "https://oa.example.test/?token=secret", "/law-api", 2, 30_000, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessOaProperties(
                "http://8.8.8.8:48080", "/law-api", 2, 30_000, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessOaProperties(
                "https://oa.example.test", "law-api", 2, 30_000, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessOaProperties(
                "https://oa.example.test", "/law-api/../admin", 2, 30_000, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessOaProperties(
                "https://oa.example.test", "/law-api", 0, 30_000, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessOaProperties(
                "https://oa.example.test", "/law-api", 2, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsBusinessProfileValuesWithoutLeakingAnEndpointIntoOtherProfiles() {
        BusinessOaProperties properties = new BusinessOaProperties();

        assertThat(properties.apiPrefix()).isEqualTo("/law-api");
        assertThat(properties.platformId()).isEqualTo(2);
        assertThat(properties.requestTimeoutMs()).isEqualTo(30_000);
        assertThat(properties.baseUrl()).isBlank();
    }
}
