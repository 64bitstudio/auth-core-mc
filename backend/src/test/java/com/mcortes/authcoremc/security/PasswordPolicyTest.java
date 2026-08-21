package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcortes.authcoremc.service.WeakPasswordException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsAPasswordWithLettersAndDigitsAtMinimumLength() {
        assertThatCode(() -> PasswordPolicy.validate("abcd1234")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAPasswordShorterThanEightCharacters() {
        assertThatThrownBy(() -> PasswordPolicy.validate("abc123"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("8 characters");
    }

    @Test
    void rejectsAPasswordWithOnlyLetters() {
        assertThatThrownBy(() -> PasswordPolicy.validate("abcdefgh"))
                .isInstanceOf(WeakPasswordException.class)
                .hasMessageContaining("letter and one digit");
    }

    @Test
    void rejectsAPasswordWithOnlyDigits() {
        assertThatThrownBy(() -> PasswordPolicy.validate("12345678"))
                .isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void rejectsANullPassword() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(WeakPasswordException.class);
    }
}
