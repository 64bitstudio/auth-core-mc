package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;

@DataRedisTest
@Import({TestcontainersConfiguration.class, RedisTokenStore.class})
class RedisTokenStoreTest {

    @Autowired
    private RedisTokenStore tokenStore;

    @Test
    void aTokenCanBeConsumedOnceForTheValueItWasIssuedFor() {
        String token = tokenStore.issue("email-verify", "user-123", Duration.ofMinutes(5));

        assertThat(tokenStore.consume("email-verify", token)).contains("user-123");
    }

    @Test
    void aTokenCannotBeConsumedTwice() {
        String token = tokenStore.issue("email-verify", "user-123", Duration.ofMinutes(5));

        tokenStore.consume("email-verify", token);

        assertThat(tokenStore.consume("email-verify", token)).isEmpty();
    }

    @Test
    void aTokenCannotBeConsumedUnderADifferentPurpose() {
        String token = tokenStore.issue("email-verify", "user-123", Duration.ofMinutes(5));

        assertThat(tokenStore.consume("password-reset", token)).isEmpty();
    }

    @Test
    void anUnknownTokenIsNotFound() {
        assertThat(tokenStore.consume("email-verify", "does-not-exist")).isEmpty();
    }

    @Test
    void twoIssuedTokensAreDifferent() {
        String tokenA = tokenStore.issue("email-verify", "user-123", Duration.ofMinutes(5));
        String tokenB = tokenStore.issue("email-verify", "user-123", Duration.ofMinutes(5));

        assertThat(tokenA).isNotEqualTo(tokenB);
    }
}
