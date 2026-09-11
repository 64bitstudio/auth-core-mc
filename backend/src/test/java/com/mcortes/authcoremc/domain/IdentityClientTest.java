package com.mcortes.authcoremc.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ticket 055: {@code ownLoginUiRedirectUri()} is the single source of truth
 * {@code SocialLoginSuccessHandler}/{@code SocialLoginFailureHandler} both
 * call instead of each repeating the same branch — covered here at the
 * domain level so neither handler test needs to re-exercise every case.
 */
class IdentityClientTest {

    private static Tenant tenantFixture() {
        return new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    }

    @Test
    void aClientThatDoesNotHostItsOwnUiHasNoOwnRedirectUri() {
        IdentityClient client = new IdentityClient(
                tenantFixture(), "acme-web-app", null, true, List.of("https://acme.example.com/callback"));

        assertThat(client.ownLoginUiRedirectUri()).isEmpty();
    }

    @Test
    void aClientHostingItsOwnUiReturnsItsFirstRedirectUri() {
        IdentityClient client = IdentityClient.builder(
                        tenantFixture(),
                        "galgoth-studio",
                        true,
                        List.of("https://studio.galgoth.64bitstudio.com/auth/callback", "https://second.example.com"))
                .hostsOwnLoginUi(true)
                .build();

        assertThat(client.ownLoginUiRedirectUri())
                .contains("https://studio.galgoth.64bitstudio.com/auth/callback");
    }

    @Test
    void hostsOwnUiButWithNoRedirectUriConfiguredIsTreatedAsNotHostingItsOwnUi() {
        IdentityClient client = IdentityClient.builder(tenantFixture(), "misconfigured", true, List.of())
                .hostsOwnLoginUi(true)
                .build();

        assertThat(client.ownLoginUiRedirectUri()).isEmpty();
    }

    @Test
    void hostsOwnUiWithNullRedirectUrisIsTreatedAsNotHostingItsOwnUi() {
        IdentityClient client = IdentityClient.builder(tenantFixture(), "misconfigured", true, null)
                .hostsOwnLoginUi(true)
                .build();

        assertThat(client.ownLoginUiRedirectUri()).isEmpty();
    }
}
