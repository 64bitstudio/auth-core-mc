package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;

@DataRedisTest
@Import({TestcontainersConfiguration.class, Cooldown.class})
class CooldownTest {

    @Autowired
    private Cooldown cooldown;

    @Test
    void isNotActiveBeforeItStarts() {
        assertThat(cooldown.isActive("resend:user-1")).isFalse();
    }

    @Test
    void isActiveImmediatelyAfterStarting() {
        cooldown.start("resend:user-2", Duration.ofMinutes(1));

        assertThat(cooldown.isActive("resend:user-2")).isTrue();
    }

    @Test
    void differentKeysAreIndependent() {
        cooldown.start("resend:user-3", Duration.ofMinutes(1));

        assertThat(cooldown.isActive("resend:user-4")).isFalse();
    }
}
