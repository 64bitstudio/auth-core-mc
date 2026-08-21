package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
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
 */
@Service
public class TotpService {

    private final UserRepository userRepository;
    private final SecretEncryptor secretEncryptor;
    private final StringRedisTemplate redis;

    public TotpService(UserRepository userRepository, SecretEncryptor secretEncryptor, StringRedisTemplate redis) {
        this.userRepository = userRepository;
        this.secretEncryptor = secretEncryptor;
        this.redis = redis;
    }

    /** @return the plain-text secret, to show once as a QR/manual-entry code — never stored or logged in plain text. */
    public String enroll(User user) {
        String secret = Totp.generateSecret();
        user.enrollTotpSecret(secretEncryptor.encrypt(secret));
        userRepository.save(user);
        return secret;
    }

    public void verify(User user, String code) {
        if (user.getTotpSecretEncrypted() == null) {
            throw new InvalidTokenException("TOTP is not enrolled for this user");
        }
        String secret = secretEncryptor.decrypt(user.getTotpSecretEncrypted());

        long window = Totp.matchedWindow(secret, code);
        if (window < 0) {
            throw new InvalidTokenException("Invalid or expired code");
        }

        String replayKey = "totp-used:" + user.getId() + ":" + window;
        Boolean firstUse = redis.opsForValue().setIfAbsent(replayKey, "1", Duration.ofSeconds(90));
        if (!Boolean.TRUE.equals(firstUse)) {
            throw new InvalidTokenException("This code has already been used");
        }
    }
}
