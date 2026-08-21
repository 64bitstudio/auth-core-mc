package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.service.TooManyAttemptsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;

@DataRedisTest
@Import({TestcontainersConfiguration.class, LoginRateLimiter.class})
class LoginRateLimiterTest {

    @Autowired
    private LoginRateLimiter rateLimiter;

    private final String tenantId = UUID.randomUUID().toString();

    @Test
    void allowsAttemptsUnderTheThreshold() {
        String identifier = "under-threshold@example.com";
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS - 1; i++) {
            rateLimiter.recordFailure(tenantId, identifier);
        }

        assertThatCode(() -> rateLimiter.checkAllowed(tenantId, identifier)).doesNotThrowAnyException();
    }

    @Test
    void blocksOnceTheThresholdIsReached() {
        String identifier = "at-threshold@example.com";
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            rateLimiter.recordFailure(tenantId, identifier);
        }

        assertThatThrownBy(() -> rateLimiter.checkAllowed(tenantId, identifier))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void aSuccessfulLoginResetsTheCounter() {
        String identifier = "recovers@example.com";
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            rateLimiter.recordFailure(tenantId, identifier);
        }

        rateLimiter.recordSuccess(tenantId, identifier);

        assertThatCode(() -> rateLimiter.checkAllowed(tenantId, identifier)).doesNotThrowAnyException();
    }

    @Test
    void differentIdentifiersHaveIndependentCounters() {
        String blockedIdentifier = "blocked@example.com";
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            rateLimiter.recordFailure(tenantId, blockedIdentifier);
        }

        assertThatCode(() -> rateLimiter.checkAllowed(tenantId, "fresh@example.com"))
                .doesNotThrowAnyException();
    }
}
