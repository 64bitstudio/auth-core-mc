package com.mcortes.authcoremc.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Generic one-time-use, expiring token store backed by Redis — the shared
 * mechanism behind email verification, change-email confirmation (ticket
 * 003), and password reset (ticket 004): all three are "prove you received
 * this link within some tenant-configurable window" flows, so they share
 * one implementation instead of three copies of the same Redis logic.
 *
 * <p>{@code purpose} namespaces different flows so a token minted for one
 * (e.g. "email-verify") can't be replayed against another (e.g.
 * "password-reset") even if the random value collided (astronomically
 * unlikely on its own, but namespacing costs nothing and removes the
 * possibility entirely).
 */
@Component
public class RedisTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public RedisTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Issues a new random token bound to {@code value}, valid for {@code ttl}. */
    public String issue(String purpose, String value, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(purpose, token), value, ttl);
        return token;
    }

    /**
     * Consumes a token: returns its bound value if it exists and hasn't
     * expired, and atomically deletes it either way (one-time use — a
     * token can't be replayed even if consumption fails downstream).
     */
    public Optional<String> consume(String purpose, String token) {
        String key = key(purpose, token);
        String value = redis.opsForValue().get(key);
        redis.delete(key);
        return Optional.ofNullable(value);
    }

    /**
     * Reads a token's bound value WITHOUT consuming it — ticket 046, for
     * "resend the OTP code" while a {@code pendingToken} is still awaiting
     * verification: that action must not burn the one-time token the user
     * still needs for the actual verify step. A plain Redis {@code GET}
     * doesn't touch the key's TTL, so peeking never extends (or shortens)
     * how long the token stays valid.
     */
    public Optional<String> peek(String purpose, String token) {
        return Optional.ofNullable(redis.opsForValue().get(key(purpose, token)));
    }

    private String key(String purpose, String token) {
        return "token:%s:%s".formatted(purpose, token);
    }
}
