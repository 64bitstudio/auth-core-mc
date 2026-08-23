package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.LoginRateLimiter;
import com.mcortes.authcoremc.security.SecretEncryptor;
import com.mcortes.authcoremc.security.Totp;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * TOTP enrollment and verification. The secret is stored encrypted (not
 * hashed — it must be read back to compute codes, see SecretEncryptor's
 * Javadoc). Replay protection: a code that matched a given 30s window
 * cannot be accepted again for that same window, even though {@link Totp}
 * itself tolerates ±1 window of clock drift (ticket 005's explicit
 * acceptance criterion).
 *
 * <p><b>Ticket 047 — rate-limiting:</b> {@code verify} reuses {@link
 * LoginRateLimiter} exactly the way {@link OtpService#verifyOtp} already
 * does — same mechanism, not a parallel implementation. Flagged as a real
 * security finding by the ticket 045 report: until now, a TOTP code could
 * be brute-forced with unlimited guesses (a 6-digit code only has
 * 1,000,000 possible values). The "not enrolled" check below stays
 * unguarded — it can't happen from a real flow (a user without an enrolled
 * secret never reaches this method), only from tampered/inconsistent
 * state, so it isn't part of the actual guessing surface this ticket
 * closes.
 */
@Service
public class TotpService {

    private final UserRepository userRepository;
    private final SecretEncryptor secretEncryptor;
    private final StringRedisTemplate redis;
    private final LoginRateLimiter attemptLimiter;

    public TotpService(
            UserRepository userRepository,
            SecretEncryptor secretEncryptor,
            StringRedisTemplate redis,
            LoginRateLimiter attemptLimiter) {
        this.userRepository = userRepository;
        this.secretEncryptor = secretEncryptor;
        this.redis = redis;
        this.attemptLimiter = attemptLimiter;
    }

    /** @return the plain-text secret, to show once as a QR/manual-entry code — never stored or logged in plain text. */
    public String enroll(User user) {
        String secret = Totp.generateSecret();
        user.enrollTotpSecret(secretEncryptor.encrypt(secret));
        userRepository.save(user);
        return secret;
    }

    public void verify(User user, String code) {
        String tenantKey = user.getTenant().getId().toString();
        // Own namespace ("totp:"), distinct from OtpService's own "otp:" —
        // a user could conceivably have both methods' history in Redis
        // (e.g. after switching their preferred method), and the two guess
        // surfaces are independent codes with independent limits.
        String attemptKey = "totp:" + user.getId();
        attemptLimiter.checkAllowed(tenantKey, attemptKey);

        if (user.getTotpSecretEncrypted() == null) {
            throw new InvalidTokenException("TOTP is not enrolled for this user");
        }
        String secret = secretEncryptor.decrypt(user.getTotpSecretEncrypted());

        long window = Totp.matchedWindow(secret, code);
        if (window < 0) {
            attemptLimiter.recordFailure(tenantKey, attemptKey);
            throw new InvalidTokenException("Invalid or expired code");
        }

        String replayKey = "totp-used:" + user.getId() + ":" + window;
        Boolean firstUse = redis.opsForValue().setIfAbsent(replayKey, "1", Duration.ofSeconds(90));
        if (!Boolean.TRUE.equals(firstUse)) {
            attemptLimiter.recordFailure(tenantKey, attemptKey);
            throw new InvalidTokenException("This code has already been used");
        }

        attemptLimiter.recordSuccess(tenantKey, attemptKey);
    }
}
