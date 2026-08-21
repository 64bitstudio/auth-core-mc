package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TotpTest {

    @Test
    void generatesADifferentSecretEachTime() {
        assertThat(Totp.generateSecret()).isNotEqualTo(Totp.generateSecret());
    }

    @Test
    void verifiesTheCurrentValidCode() {
        String secret = Totp.generateSecret();

        assertThat(Totp.verify(secret, Totp.currentCode(secret))).isTrue();
    }

    @Test
    void rejectsAWrongCode() {
        String secret = Totp.generateSecret();

        assertThat(Totp.verify(secret, "000000")).isFalse();
    }

    @Test
    void aCodeGeneratedForOneSecretDoesNotVerifyAgainstAnother() {
        String secretA = Totp.generateSecret();
        String secretB = Totp.generateSecret();

        assertThat(Totp.verify(secretB, Totp.currentCode(secretA))).isFalse();
    }

    @Test
    void matchedWindowReturnsNonNegativeForAValidCodeAndNegativeForAnInvalidOne() {
        String secret = Totp.generateSecret();

        assertThat(Totp.matchedWindow(secret, Totp.currentCode(secret))).isGreaterThanOrEqualTo(0);
        assertThat(Totp.matchedWindow(secret, "000000")).isEqualTo(-1);
    }

    @Test
    void aSixDigitCodeIsAlwaysExactlySixDigitsEvenWithLeadingZeros() {
        String secret = Totp.generateSecret();

        assertThat(Totp.currentCode(secret)).hasSize(6).containsOnlyDigits();
    }
}
