package com.mcortes.authcoremc.notification;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends email via the Resend API (docs/ARQUITECTURA.md decision 7).
 *
 * <p>Requires {@code RESEND_API_KEY} to actually deliver anything — without
 * it, this throws rather than silently pretending to send (see the
 * silent-failure-guard philosophy: a required external dependency that
 * isn't configured must fail loudly, not degrade quietly). Until the real
 * key is configured, integration tests exercise {@link EmailSender} through
 * a test double, not this class — see docs/README.md for how to set the key.
 */
@Component
public class ResendEmailSender implements EmailSender {

    private final RestClient restClient;
    private final String fromAddress;
    private final boolean configured;

    public ResendEmailSender(
            RestClient.Builder restClientBuilder,
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from-address:}") String fromAddress) {
        this.configured = apiKey != null && !apiKey.isBlank();
        this.fromAddress = fromAddress;
        this.restClient = restClientBuilder
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        if (!configured) {
            throw new IllegalStateException(
                    "RESEND_API_KEY is not configured — cannot send email. Set it in dev-infra/.env or this "
                            + "project's own .env before triggering any email-sending flow.");
        }
        restClient
                .post()
                .uri("/emails")
                .body(Map.of("from", fromAddress, "to", to, "subject", subject, "html", htmlBody))
                .retrieve()
                .toBodilessEntity();
    }
}
