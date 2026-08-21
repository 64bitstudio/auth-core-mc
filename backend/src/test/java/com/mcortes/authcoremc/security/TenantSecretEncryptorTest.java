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
    static void startVault() {
        vault = new VaultContainer<>("hashicorp/vault:1.19")
                .withVaultToken(ROOT_TOKEN)
                .withInitCommand(
                        "secrets enable transit",
                        "write -f transit/keys/auth-core-mc-tenant-keys");
        vault.start();

        VaultTransitEncryptor vaultTransitEncryptor = new VaultTransitEncryptor(
                RestClient.builder(),
                "http://" + vault.getHost() + ":" + vault.getFirstMappedPort(),
                ROOT_TOKEN,
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
