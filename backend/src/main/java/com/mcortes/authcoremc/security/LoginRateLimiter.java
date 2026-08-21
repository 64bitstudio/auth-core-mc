package com.mcortes.authcoremc.security;

import com.mcortes.authcoremc.service.TooManyAttemptsException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed brute-force guard: after {@value #MAX_ATTEMPTS} failed login
 * attempts for the same tenant+identifier within {@value #WINDOW_MINUTES}
 * minutes, further attempts are blocked until the window expires. Redis
 * (not the database) because this needs to expire on its own and be cheap
 * to check on every login attempt — see docs/ARQUITECTURA.md decision 4.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_MINUTES = 15;

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @throws TooManyAttemptsException if this tenant+identifier is currently blocked. */
    public void checkAllowed(String tenantId, String identifier) {
        String value = redis.opsForValue().get(key(tenantId, identifier));
        int attempts = value == null ? 0 : Integer.parseInt(value);
        if (attempts >= MAX_ATTEMPTS) {
            throw new TooManyAttemptsException("Too many failed login attempts. Try again later.");
        }
    }

    public void recordFailure(String tenantId, String identifier) {
        String key = key(tenantId, identifier);
        Long attempts = redis.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redis.expire(key, Duration.ofMinutes(WINDOW_MINUTES));
        }
    }

    public void recordSuccess(String tenantId, String identifier) {
        redis.delete(key(tenantId, identifier));
    }

    private String key(String tenantId, String identifier) {
        return "login-attempts:%s:%s".formatted(tenantId, identifier.toLowerCase());
    }
}
