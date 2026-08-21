package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTest {

    @Test
    void failsLoudlyInsteadOfPretendingToSendWhenNoApiKeyIsConfigured() {
        ResendEmailSender sender = new ResendEmailSender(RestClient.builder(), "", "noreply@example.com");

        assertThatThrownBy(() -> sender.send("ada@example.com", "Subject", "<p>Body</p>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }
}
