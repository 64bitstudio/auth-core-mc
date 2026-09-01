package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Ticket platform/005: unit-level coverage of {@link VaultTransitEncryptor}'s
 * error paths (unreachable through the happy-path Testcontainers tests in
 * {@link TenantSecretEncryptorTest} / {@code AdminIdentityProviderEndToEndTest})
 * — each fails loudly by design (see the class Javadoc) rather than throwing
 * a silent NPE or returning a bogus value, and that behavior needs its own
 * proof, not just an assumption that the {@code if} guarding it is correct.
 * {@link MockRestServiceServer} stubs Vault's HTTP responses directly — no
 * real Vault needed for these malformed-response scenarios.
 */
class VaultTransitEncryptorTest {

    @Test
    void notConfiguredAtAllFailsLoudlyBeforeMakingAnyRequest() {
        VaultTransitEncryptor encryptor = new VaultTransitEncryptor(
                RestClient.builder(), "", "", "", "", "auth-core-mc-tenant-keys");

        assertThatThrownBy(() -> encryptor.wrap(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> encryptor.unwrap("vault:v1:whatever"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void appRoleLoginRespondingWithoutAUsableTokenFailsLoudly() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://vault.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://vault.invalid/v1/auth/approle/login"))
                .andRespond(withSuccess("{\"auth\": null}", MediaType.APPLICATION_JSON));
        VaultTransitEncryptor encryptor = new VaultTransitEncryptor(
                builder, "http://vault.invalid", "some-role-id", "some-secret-id", "", "auth-core-mc-tenant-keys");

        assertThatThrownBy(() -> encryptor.wrap(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not return a usable token");
        server.verify();
    }

    @Test
    void transitEncryptRespondingWithoutUsableDataFailsLoudly() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://vault.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://vault.invalid/v1/auth/approle/login"))
                .andRespond(withSuccess(
                        "{\"auth\": {\"client_token\": \"fake-token\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://vault.invalid/v1/transit/encrypt/auth-core-mc-tenant-keys"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        VaultTransitEncryptor encryptor = new VaultTransitEncryptor(
                builder, "http://vault.invalid", "some-role-id", "some-secret-id", "", "auth-core-mc-tenant-keys");

        assertThatThrownBy(() -> encryptor.wrap(new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned no usable 'data'");
        server.verify();
    }

    @Test
    void staticTokenPathIsUsedWhenAppRoleIsNotConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://vault.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // No approle/login request expected at all -- the static-token
        // (local dev-infra) path skips it entirely and goes straight to the
        // transit call, proving resolveToken() actually branches correctly
        // rather than always logging in.
        server.expect(requestTo("http://vault.invalid/v1/transit/encrypt/auth-core-mc-tenant-keys"))
                .andRespond(withSuccess(
                        "{\"data\": {\"ciphertext\": \"vault:v1:fake\"}}", MediaType.APPLICATION_JSON));
        VaultTransitEncryptor encryptor = new VaultTransitEncryptor(
                builder, "http://vault.invalid", "", "", "local-dev-static-token", "auth-core-mc-tenant-keys");

        String ciphertext = encryptor.wrap(new byte[] {1, 2, 3});

        org.assertj.core.api.Assertions.assertThat(ciphertext).isEqualTo("vault:v1:fake");
        server.verify();
    }
}
