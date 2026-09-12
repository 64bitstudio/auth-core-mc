package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ticket 056: same gap ticket 055 closed for social login, now for the
 * links sent by email/SMS ({@code EmailVerificationService},
 * {@code PasswordResetService}, {@code EmailChangeService}).
 */
class VerificationLinkFactoryTest {

    private static Tenant tenantFixture() {
        return new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    }

    @Test
    void aHostedUiClientGetsTheHostedPathUnderAppBaseUrl() {
        VerificationLinkFactory factory = new VerificationLinkFactory("https://auth.example.com");
        IdentityClient client = new IdentityClient(tenantFixture(), "acme-web-app", null, true, List.of());

        String link = factory.build(client, "/ui/verify-email/confirm", "abc123");

        assertThat(link).isEqualTo("https://auth.example.com/ui/verify-email/confirm?token=abc123");
    }

    @Test
    void anOwnUiClientGetsItsOwnOriginWithTheUiPrefixStripped() {
        VerificationLinkFactory factory = new VerificationLinkFactory("https://auth.example.com");
        IdentityClient client = IdentityClient.builder(
                        tenantFixture(), "galgoth-studio", true, List.of("https://studio.galgoth.64bitstudio.com/auth/callback"))
                .hostsOwnLoginUi(true)
                .build();

        String link = factory.build(client, "/ui/verify-email/confirm", "abc123");

        assertThat(link).isEqualTo("https://studio.galgoth.64bitstudio.com/verify-email/confirm?token=abc123");
    }

    @Test
    void anOwnUiClientWithoutAConfiguredRedirectUriFallsBackToTheHostedPath() {
        VerificationLinkFactory factory = new VerificationLinkFactory("https://auth.example.com");
        IdentityClient client =
                IdentityClient.builder(tenantFixture(), "misconfigured-client", true, List.of())
                        .hostsOwnLoginUi(true)
                        .build();

        String link = factory.build(client, "/ui/password-reset/confirm", "abc123");

        assertThat(link).isEqualTo("https://auth.example.com/ui/password-reset/confirm?token=abc123");
    }
}
