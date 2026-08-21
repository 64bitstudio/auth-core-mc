package com.mcortes.authcoremc.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A simple "don't do this again for a while" guard, backed by Redis.
 * Used to throttle resends (verification emails, OTPs, etc.) — see
 * docs/ARQUITECTURA.md. Unlike {@link RedisTokenStore}, checking a cooldown
 * never consumes it; it just expires on its own after {@code ttl}.
 */
@Component
public class Cooldown {

    private final StringRedisTemplate redis;

    public Cooldown(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isActive(String key) {
        return Boolean.TRUE.equals(redis.hasKey(cooldownKey(key)));
    }

    public void start(String key, Duration ttl) {
        redis.opsForValue().set(cooldownKey(key), "1", ttl);
    }

    private String cooldownKey(String key) {
        return "cooldown:" + key;
    }
}
