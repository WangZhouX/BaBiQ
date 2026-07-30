package com.wzx.babiq.server.business.oa.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OaPasswordEncoderTest {

    @Test
    void encodesTheWebContractWithTwoMd5RoundsAndClearsInput() {
        char[] password = "Abcdef12".toCharArray();

        String encoded = OaPasswordEncoder.encode(password);

        assertThat(encoded).isEqualTo("6d93c260d711cdb51207c420279ae936");
        assertThat(password).containsOnly('\0');
    }

    @Test
    void rejectsNonAsciiOrWeakPasswordAndStillClearsInput() {
        char[] password = "12345678".toCharArray();

        assertThatThrownBy(() -> OaPasswordEncoder.encode(password))
                .isInstanceOf(OaAuthenticationException.class)
                .hasMessage("INVALID_PASSWORD_FORMAT");
        assertThat(password).containsOnly('\0');
    }
}
