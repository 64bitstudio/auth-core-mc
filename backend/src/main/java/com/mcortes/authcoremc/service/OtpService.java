package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.SmsSender;
import com.mcortes.authcoremc.security.Cooldown;
import com.mcortes.authcoremc.security.LoginRateLimiter;
import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * One-time numeric codes sent by email or SMS. TTL comes from
 * {@code tenant.otp_ttl_seconds} (parametrizable, docs/BASE_DE_DATOS.md).
 *
 * <p>Guessing protection reuses {@link LoginRateLimiter} rather than a
 * parallel implementation — a 6-digit code only has 1,000,000 possible
 * values, so it needs the exact same "N attempts per window" defense as a
 * login password does; the class name is historical (it predates 2FA) but
 * its (tenantKey, identifierKey) → attempt-count behavior generalizes
 * cleanly to "protect any short secret from guessing."
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final Cooldown cooldown;
    private final LoginRateLimiter attemptLimiter;

    public OtpService(
            StringRedisTemplate redis,
            EmailSender emailSender,
            SmsSender smsSender,
            Cooldown cooldown,
            LoginRateLimiter attemptLimiter) {
        this.redis = redis;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.cooldown = cooldown;
        this.attemptLimiter = attemptLimiter;
    }

    public void requestOtp(User user) {
        String cooldownKey = "otp-resend:" + user.getId();
        if (cooldown.isActive(cooldownKey)) {
            throw new TooManyAttemptsException("A code was already sent recently. Please wait.");
        }

        String code = generateSixDigitCode();
        Duration ttl = Duration.ofSeconds(user.getTenant().getOtpTtlSeconds());
        redis.opsForValue().set(otpKey(user.getId().toString()), code, ttl);
        cooldown.start(cooldownKey, RESEND_COOLDOWN);

        if (user.getEmail() != null) {
            emailSender.send(user.getEmail(), "Your verification code", "<p>Your code is: " + code + "</p>");
        } else if (user.getPhone() != null) {
            smsSender.send(user.getPhone(), "Your verification code is " + code);
        }
    }

    public void verifyOtp(User user, String code) {
        String tenantKey = user.getTenant().getId().toString();
        String attemptKey = "otp:" + user.getId();
        attemptLimiter.checkAllowed(tenantKey, attemptKey);

        String stored = redis.opsForValue().get(otpKey(user.getId().toString()));
        if (stored == null || !stored.equals(code)) {
            attemptLimiter.recordFailure(tenantKey, attemptKey);
            throw new InvalidTokenException("Invalid or expired code");
        }

        attemptLimiter.recordSuccess(tenantKey, attemptKey);
        redis.delete(otpKey(user.getId().toString())); // one-time use
    }

    private static String generateSixDigitCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String otpKey(String userId) {
        return "otp:" + userId;
    }
}
