package com.mcortes.authcoremc.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ticket 055: the single implementation both {@code SocialLoginSuccessHandler}
 * and {@code SocialLoginFailureHandler} call instead of each building this
 * URL themselves — the first draft of this ticket had three near-identical
 * copies of this exact logic across the two classes.
 */
class SocialLoginRedirectTest {

    private static Tenant tenantFixture() {
        return new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    }

    @Test
    void aHostedUiClientGetsTheParamAppendedToTheHostedPathWithClientId() {
        IdentityClient client = new IdentityClient(tenantFixture(), "acme-web-app", null, true, List.of());

        String uri = SocialLoginRedirect.buildUri(client, "/ui/social-callback", "code", "one-time-code");

        assertThat(uri)
                .startsWith("/ui/social-callback")
                .contains("client_id=acme-web-app")
                .contains("code=one-time-code");
    }

    @Test
    void anOwnUiClientGetsTheParamAppendedToItsOwnRedirectUriWithoutClientId() {
        IdentityClient client = IdentityClient.builder(
                        tenantFixture(), "galgoth-studio", true, List.of("https://studio.galgoth.64bitstudio.com/auth/callback"))
                .hostsOwnLoginUi(true)
                .build();

        String uri = SocialLoginRedirect.buildUri(client, "/ui/login", "error", "social_login_cancelled");

        assertThat(uri)
                .startsWith("https://studio.galgoth.64bitstudio.com/auth/callback")
                .contains("error=social_login_cancelled")
                .doesNotContain("client_id=");
    }
}
