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
 * <p>Ticket platform/005 (docs/definiciones/vault-secrets-manager-vm.md,
 * HU-7): the real, deployed environments (DEV/QA/PROD on the shared VM)
 * authenticate via a Vault AppRole ({@code auth-core-mc-backend},
 * least-privilege — only {@code encrypt}/{@code decrypt} on the
 * {@code auth-core-mc-tenant-keys} transit key, see
 * {@code platform/deploy/vm-infra/vault/bootstrap-auth-core-mc-backend-approle.sh}),
 * logging in fresh on every {@link #wrap}/{@link #unwrap} call rather than
 * caching a token — this is a config-time operation (a tenant setting up a
 * social login secret), not a request hot path, so the extra round-trip is
 * a reasonable trade for never having to reason about token
 * expiry/renewal in a long-running service. The {@code role_id} is not a
 * secret by design; only {@code secret_id} is injected as an environment
 * variable at deploy time (same mechanism {@code DB_PASSWORD} already
 * used, extended in {@code corePipeline.groovy}).
 *
 * <p><b>Local development is explicitly out of scope for this
 * (docs/definiciones/vault-secrets-manager-vm.md, "No incluye": "Retirar
 * el Vault de la Mac — sigue existiendo para desarrollo local")</b> — the
 * legacy static-token path ({@code vault.token} / {@code VAULT_ROOT_TOKEN},
 * pointed at {@code ~/dev-infra}'s own Vault) still works unchanged, so
 * Marco's local setup needs no new bootstrap. AppRole takes priority when
 * both are configured; the token path exists purely for that local
 * convenience, never in a deployed environment.
 *
 * <p>Deliberately a thin {@link RestClient} call against Vault's HTTP API
 * (same pattern as {@code ResendEmailSender}) instead of the
 * spring-vault-core client library — AppRole login plus two Transit
 * operations don't need that library's full auth-method/lease machinery.
 */
@Component
public class VaultTransitEncryptor {

    private final RestClient restClient;
    private final String roleId;
    private final String secretId;
    private final String staticToken;
    private final String keyName;
    private final boolean appRoleConfigured;
    private final boolean configured;

    public VaultTransitEncryptor(
            RestClient.Builder restClientBuilder,
            @Value("${vault.address:}") String vaultAddress,
            @Value("${vault.role-id:}") String roleId,
            @Value("${vault.secret-id:}") String secretId,
            @Value("${vault.token:}") String staticToken,
            @Value("${vault.transit-key-name:auth-core-mc-tenant-keys}") String keyName) {
        boolean hasAddress = vaultAddress != null && !vaultAddress.isBlank();
        this.appRoleConfigured =
                hasAddress && roleId != null && !roleId.isBlank() && secretId != null && !secretId.isBlank();
        boolean tokenConfigured = hasAddress && staticToken != null && !staticToken.isBlank();
        this.configured = appRoleConfigured || tokenConfigured;
        this.roleId = roleId;
        this.secretId = secretId;
        this.staticToken = staticToken;
        this.keyName = keyName;
        this.restClient = restClientBuilder.baseUrl(vaultAddress == null ? "" : vaultAddress).build();
    }

    /** Wraps a freshly generated tenant data-key — returns Vault's own ciphertext token (e.g. "vault:v1:..."), safe to store as-is. */
    @SuppressWarnings("unchecked")
    public String wrap(byte[] plaintext) {
        requireConfigured();
        String vaultToken = resolveToken();
        Map<String, Object> response = restClient
                .post()
                .uri("/v1/transit/encrypt/{key}", keyName)
                .header("X-Vault-Token", vaultToken)
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
        String vaultToken = resolveToken();
        Map<String, Object> response = restClient
                .post()
                .uri("/v1/transit/decrypt/{key}", keyName)
                .header("X-Vault-Token", vaultToken)
                .body(Map.of("ciphertext", wrapped))
                .retrieve()
                .body(Map.class);
        Map<String, Object> data = requireResponseData(response, "unwrap");
        String base64Plaintext = (String) data.get("plaintext");
        return Base64.getDecoder().decode(base64Plaintext);
    }

    /** AppRole (deployed environments) takes priority; falls back to the static local-dev token. Never logs either. */
    private String resolveToken() {
        return appRoleConfigured ? login() : staticToken;
    }

    /**
     * Logs in via AppRole and returns a fresh, short-lived Vault token. Never
     * logs the token itself — only fails loudly (message names the failure
     * mode, never the credential values) if login doesn't produce one.
     */
    @SuppressWarnings("unchecked")
    private String login() {
        Map<String, Object> response = restClient
                .post()
                .uri("/v1/auth/approle/login")
                .body(Map.of("role_id", roleId, "secret_id", secretId))
                .retrieve()
                .body(Map.class);
        Object auth = response == null ? null : response.get("auth");
        Object token = auth instanceof Map ? ((Map<String, Object>) auth).get("client_token") : null;
        if (!(token instanceof String tokenStr) || tokenStr.isBlank()) {
            throw new IllegalStateException(
                    "Vault AppRole login (auth-core-mc-backend) did not return a usable token — check Vault is "
                            + "unsealed and vault.role-id/vault.secret-id are correct.");
        }
        return tokenStr;
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
                    "Vault is not configured for Transit — set either vault.address+vault.role-id+vault.secret-id "
                            + "(VAULT_ADDR/VAULT_ROLE_ID/VAULT_SECRET_ID, deployed environments) or "
                            + "vault.address+vault.token (VAULT_ADDR/VAULT_ROOT_TOKEN, local dev-infra Vault) before "
                            + "creating a tenant or configuring its secrets.");
        }
    }
}
