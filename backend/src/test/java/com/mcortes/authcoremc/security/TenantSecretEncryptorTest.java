package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

/**
 * Ticket 017: proves envelope encryption end to end against a real Vault
 * (Testcontainers — hermetic, same "own container per run" philosophy as
 * the Postgres tests, not pointed at the shared local dev-infra Vault
 * which could be sealed/unavailable when this runs).
 */
@Testcontainers
class TenantSecretEncryptorTest {

    private static final String ROOT_TOKEN = "test-root-token";

    private static VaultContainer<?> vault;
    private static TenantSecretEncryptor encryptor;

    @BeforeAll
    static void startVault() throws Exception {
        vault = new VaultContainer<>("hashicorp/vault:1.19")
                .withVaultToken(ROOT_TOKEN)
                .withInitCommand(
                        "secrets enable transit",
                        "write -f transit/keys/auth-core-mc-tenant-keys",
                        "auth enable approle");
        vault.start();

        // Ticket platform/005 (HU-7): el encryptor de verdad usa AppRole,
        // no un token estático -- se replica el mismo mecanismo aquí
        // (policy acotada a encrypt/decrypt sobre la llave de prueba,
        // AppRole propia) en vez de seguir pasándole el token root
        // directamente, para que este test siga probando el camino real.
        vault.execInContainer(
                "/bin/sh",
                "-c",
                "echo 'path \"transit/encrypt/auth-core-mc-tenant-keys\" { capabilities = [\"update\"] }\n"
                        + "path \"transit/decrypt/auth-core-mc-tenant-keys\" { capabilities = [\"update\"] }' "
                        + "| vault policy write test-transit-policy -");
        vault.execInContainer(
                "vault",
                "write",
                "auth/approle/role/test-role",
                "token_policies=test-transit-policy",
                "token_ttl=10m");
        String roleId = vault.execInContainer("vault", "read", "-field=role_id", "auth/approle/role/test-role/role-id")
                .getStdout()
                .trim();
        String secretId = vault.execInContainer(
                        "vault", "write", "-field=secret_id", "-f", "auth/approle/role/test-role/secret-id")
                .getStdout()
                .trim();

        VaultTransitEncryptor vaultTransitEncryptor = new VaultTransitEncryptor(
                RestClient.builder(),
                "http://" + vault.getHost() + ":" + vault.getFirstMappedPort(),
                roleId,
                secretId,
                "", // vault.token -- not used, this test exercises the AppRole path
                "auth-core-mc-tenant-keys");
        encryptor = new TenantSecretEncryptor(vaultTransitEncryptor);
    }

    @AfterAll
    static void stopVault() {
        vault.stop();
    }

    @Test
    void aSecretEncryptedForOneTenantDecryptsBackToTheSamePlaintext() {
        String wrappedDataKey = encryptor.newWrappedDataKey();

        String ciphertext = encryptor.encrypt(wrappedDataKey, "google-client-secret-abc123");
        String decrypted = encryptor.decrypt(wrappedDataKey, ciphertext);

        assertThat(decrypted).isEqualTo("google-client-secret-abc123");
        assertThat(ciphertext).doesNotContain("google-client-secret-abc123");
    }

    @Test
    void twoTenantsGetIndependentDataKeysThatCannotDecryptEachOthersSecrets() {
        String wrappedKeyA = encryptor.newWrappedDataKey();
        String wrappedKeyB = encryptor.newWrappedDataKey();

        String ciphertextForA = encryptor.encrypt(wrappedKeyA, "tenant-a-secret");

        assertThatThrownBy(() -> encryptor.decrypt(wrappedKeyB, ciphertextForA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        String wrappedDataKey = encryptor.newWrappedDataKey();

        String first = encryptor.encrypt(wrappedDataKey, "same-secret");
        String second = encryptor.encrypt(wrappedDataKey, "same-secret");

        // Different IV each time (see class Javadoc) — same plaintext, same
        // key, but ciphertext must never repeat.
        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(wrappedDataKey, first)).isEqualTo("same-secret");
        assertThat(encryptor.decrypt(wrappedDataKey, second)).isEqualTo("same-secret");
    }
}
