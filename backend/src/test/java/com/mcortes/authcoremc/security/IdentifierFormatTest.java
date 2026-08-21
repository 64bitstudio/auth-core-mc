package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentifierFormatTest {

    @Test
    void acceptsAWellFormedEmail() {
        assertThat(IdentifierFormat.isValidEmail("ada@example.com")).isTrue();
    }

    @Test
    void rejectsAnEmailWithoutAtSign() {
        assertThat(IdentifierFormat.isValidEmail("ada.example.com")).isFalse();
    }

    @Test
    void rejectsANullEmail() {
        assertThat(IdentifierFormat.isValidEmail(null)).isFalse();
    }

    @Test
    void acceptsAWellFormedE164Phone() {
        assertThat(IdentifierFormat.isValidPhone("+525512345678")).isTrue();
    }

    @Test
    void rejectsAPhoneWithoutLeadingPlus() {
        assertThat(IdentifierFormat.isValidPhone("525512345678")).isFalse();
    }

    @Test
    void rejectsAPhoneStartingWithZeroAfterThePlus() {
        assertThat(IdentifierFormat.isValidPhone("+05512345678")).isFalse();
    }

    @Test
    void rejectsANullPhone() {
        assertThat(IdentifierFormat.isValidPhone(null)).isFalse();
    }
}
