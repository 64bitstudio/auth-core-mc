package com.mcortes.authcoremc.security;

import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wraps/unwraps a small payload (a tenant's AES data-key, see {@link
 * TenantSecretEncryptor}) via HashiCorp Vault's Transit secrets engine
 * (ticket 017) — Vault never sees the actual secret content, only ever
 * wraps/unwraps the data-key itself.
 *
 * <p>Deliberately a thin {@link RestClient} call against Vault's HTTP API
 * (same pattern as {@code ResendEmailSender}) instead of the
 * spring-vault-core client library — the only two operations needed
 * (transit encrypt/decrypt) don't need that library's auth-method/lease
 * machinery.
 */
@Component
public class VaultTransitEncryptor {

    private final RestClient restClient;
    private final String keyName;
    private final boolean configured;

    public VaultTransitEncryptor(
            RestClient.Builder restClientBuilder,
            @Value("${vault.address:}") String vaultAddress,
            @Value("${vault.token:}") String vaultToken,
            @Value("${vault.transit-key-name:auth-core-mc-tenant-keys}") String keyName) {
        this.configured =
                vaultAddress != null && !vaultAddress.isBlank() && vaultToken != null && !vaultToken.isBlank();
        this.keyName = keyName;
        this.restClient = restClientBuilder
                .baseUrl(vaultAddress == null ? "" : vaultAddress)
                .defaultHeader("X-Vault-Token", vaultToken == null ? "" : vaultToken)
                .build();
    }

    /** Wraps a freshly generated tenant data-key — returns Vault's own ciphertext token (e.g. "vault:v1:..."), safe to store as-is. */
    @SuppressWarnings("unchecked")
    public String wrap(byte[] plaintext) {
        requireConfigured();
        Map<String, Object> response = restClient
                .post()
                .uri("/v1/transit/encrypt/{key}", keyName)
                .body(Map.of("plaintext", Base64.getEncoder().encodeToString(plaintext)))
                .retrieve()
                .body(Map.class);
        Map<String, Object> data = requireResponseData(response, "wrap");
        return (String) data.get("ciphertext");
    }

    /** Unwraps a previously-wrapped data-key back to its raw bytes. */
    @SuppressWarnings("unchecked")
    public byte[] unwrap(String wrapped) {
        requireConfigured();
        Map<String, Object> response = restClient
                .post()
                .uri("/v1/transit/decrypt/{key}", keyName)
                .body(Map.of("ciphertext", wrapped))
                .retrieve()
                .body(Map.class);
        Map<String, Object> data = requireResponseData(response, "unwrap");
        String base64Plaintext = (String) data.get("plaintext");
        return Base64.getDecoder().decode(base64Plaintext);
    }

    /** Fails loudly (not a silent NPE) when Vault's response is missing or malformed — e.g. an unexpected empty body that {@code retrieve()} didn't already turn into an HTTP error. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> requireResponseData(Map<String, Object> response, String operation) {
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof Map)) {
            throw new IllegalStateException(
                    "Vault transit " + operation + " returned no usable 'data' in its response — check Vault is "
                            + "unsealed and the transit key '" + keyName + "' exists.");
        }
        return (Map<String, Object>) data;
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException(
                    "vault.address/vault.token (VAULT_ADDR/VAULT_ROOT_TOKEN) are not configured — cannot "
                            + "wrap/unwrap tenant data-keys. Set them in dev-infra/.env before creating a "
                            + "tenant or configuring its secrets.");
        }
    }
}
