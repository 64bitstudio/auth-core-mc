package com.mcortes.authcoremc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserTest {

    private final Tenant tenant =
            new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void allowsRegistrationWithOnlyEmail() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");

        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getPhone()).isNull();
    }

    @Test
    void allowsRegistrationWithOnlyPhone() {
        User user = new User(tenant, null, "+525512345678", "Ada", "Lovelace", "hash");

        assertThat(user.getPhone()).isEqualTo("+525512345678");
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void rejectsRegistrationWithNeitherEmailNorPhone() {
        assertThatThrownBy(() -> new User(tenant, null, null, "Ada", "Lovelace", "hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email or a phone");
    }

    @Test
    void rejectsRegistrationWithBlankEmailAndBlankPhone() {
        assertThatThrownBy(() -> new User(tenant, "  ", " ", "Ada", "Lovelace", "hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newUsersAreUnverifiedByDefault() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.isPhoneVerified()).isFalse();
    }
}
