package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TwilioSmsSenderTest {

    @Test
    void failsLoudlyInsteadOfPretendingToSendWhenNotConfigured() {
        TwilioSmsSender sender = new TwilioSmsSender(RestClient.builder(), "", "", "+10000000000");

        assertThatThrownBy(() -> sender.send("+525512345678", "your code is 123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TWILIO_ACCOUNT_SID");
    }
}
