package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerificationLinkFactoryTest {

    @Test
    void buildsALinkWithTheTokenAsAQueryParameter() {
        VerificationLinkFactory factory = new VerificationLinkFactory("https://auth.example.com");

        String link = factory.build("/ui/verify-email/confirm", "abc123");

        assertThat(link).isEqualTo("https://auth.example.com/ui/verify-email/confirm?token=abc123");
    }
}
