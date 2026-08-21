package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.VerificationLinkFactory;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.Cooldown;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account (email) verification. The link's lifetime is
 * {@code tenant.email_verification_ttl_seconds} — parametrizable per tenant,
 * per the original requirement that every TTL in this service be
 * configurable (docs/BASE_DE_DATOS.md).
 */
@Service
public class EmailVerificationService {

    private static final String PURPOSE = "email-verify";
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final RedisTokenStore tokenStore;
    private final EmailSender emailSender;
    private final Cooldown cooldown;
    private final VerificationLinkFactory linkFactory;

    public EmailVerificationService(
            UserRepository userRepository,
            RedisTokenStore tokenStore,
            EmailSender emailSender,
            Cooldown cooldown,
            VerificationLinkFactory linkFactory) {
        this.userRepository = userRepository;
        this.tokenStore = tokenStore;
        this.emailSender = emailSender;
        this.cooldown = cooldown;
        this.linkFactory = linkFactory;
    }

    /** @throws TooManyAttemptsException if called again before the resend cooldown elapses. */
    public void requestVerification(User user) {
        if (user.getEmail() == null) {
            throw new IllegalArgumentException("User has no email to verify");
        }
        String cooldownKey = PURPOSE + ":" + user.getId();
        if (cooldown.isActive(cooldownKey)) {
            throw new TooManyAttemptsException("A verification email was already sent recently. Please wait.");
        }

        Duration ttl = Duration.ofSeconds(user.getTenant().getEmailVerificationTtlSeconds());
        String token = tokenStore.issue(PURPOSE, user.getId().toString(), ttl);
        cooldown.start(cooldownKey, RESEND_COOLDOWN);

        String link = linkFactory.build("/ui/verify-email/confirm", token);
        emailSender.send(user.getEmail(), "Verify your account", "<p>Click to verify your account: " + link + "</p>");
    }

    @Transactional
    public void confirmVerification(String token) {
        String userId = tokenStore
                .consume(PURPOSE, token)
                .orElseThrow(() -> new InvalidTokenException("Verification link is invalid or has expired"));

        User user = userRepository
                .findById(UUID.fromString(userId))
                .orElseThrow(() -> new InvalidTokenException("Verification link is invalid or has expired"));

        user.markEmailVerified();
        userRepository.save(user);
    }
}
