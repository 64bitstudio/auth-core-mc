package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Ticket 064 -- mismo criterio de "falla ruidoso, nunca en silencio" que {@code ResendEmailSenderTest}/{@code TwilioSmsSenderTest}. */
class GalgothStudioPurgeClientTest {

    @Test
    void failsLoudlyInsteadOfSilentlySkippingThePurgeWhenNotConfigured() {
        GalgothStudioPurgeClient client = new GalgothStudioPurgeClient(RestClient.builder(), "", "");
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> client.purgeProjects(userId))
                .isInstanceOf(AccountDeletionFailedException.class)
                .hasMessageContaining("GALGOTH_STUDIO_INTERNAL_URL")
                .hasMessageContaining("GALGOTH_INTERNAL_SECRET");
    }

    @Test
    void failsLoudlyWhenUrlIsSetButSecretIsMissing() {
        GalgothStudioPurgeClient client =
                new GalgothStudioPurgeClient(RestClient.builder(), "https://studio-dev.galgoth.64bitstudio.com", "");
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> client.purgeProjects(userId)).isInstanceOf(AccountDeletionFailedException.class);
    }
}
