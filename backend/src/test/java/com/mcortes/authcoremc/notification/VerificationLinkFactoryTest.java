package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerificationLinkFactoryTest {

    @Test
    void buildsALinkWithTheTokenAsAQueryParameter() {
        VerificationLinkFactory factory = new VerificationLinkFactory("https://auth.example.com");

        String link = factory.build("/api/v1/verify-email/confirm", "abc123");

        assertThat(link).isEqualTo("https://auth.example.com/api/v1/verify-email/confirm?token=abc123");
    }
}
