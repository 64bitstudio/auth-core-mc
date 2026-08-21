package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretEncryptorTest {

    private final SecretEncryptor encryptor = new SecretEncryptor("RrHtxQQxrBRFOMu/D1TuAqDeq/eANE++OIlU9tkFhbY=");

    @Test
    void decryptingWhatWasEncryptedReturnsTheOriginalPlaintext() {
        String ciphertext = encryptor.encrypt("my-totp-secret");

        assertThat(encryptor.decrypt(ciphertext)).isEqualTo("my-totp-secret");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        String first = encryptor.encrypt("my-totp-secret");
        String second = encryptor.encrypt("my-totp-secret");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void ciphertextIsNotHumanReadable() {
        String ciphertext = encryptor.encrypt("my-totp-secret");

        assertThat(ciphertext).doesNotContain("my-totp-secret");
    }

    @Test
    void garbageInputFailsToDecryptRatherThanReturningNonsenseSilently() {
        assertThatThrownBy(() -> encryptor.decrypt("not-valid-base64-ciphertext!!"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void decryptingWithADifferentKeyFails() {
        String ciphertext = encryptor.encrypt("my-totp-secret");
        SecretEncryptor otherEncryptor = new SecretEncryptor("Q0mB2p8h+3l3v6YyF2q9x+3cJb2m9YkP1zR8sT5uH8w=");

        assertThatThrownBy(() -> otherEncryptor.decrypt(ciphertext)).isInstanceOf(RuntimeException.class);
    }
}
