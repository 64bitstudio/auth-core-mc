package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.SmsSender;
import com.mcortes.authcoremc.notification.VerificationLinkFactory;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.Cooldown;
import com.mcortes.authcoremc.security.IdentifierFormat;
import com.mcortes.authcoremc.security.PasswordPolicy;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Forgot my password" flow. The request side is a deliberate black box:
 * {@link #requestReset} NEVER throws and NEVER behaves observably
 * differently for an identifier that exists vs. one that doesn't (ticket
 * 004's explicit requirement) — unlike ticket 003's verification resend,
 * which safely reveals a cooldown because the caller already supplied a
 * real userId it's presumed to own. Here the caller supplies only an
 * email/phone guess, so even "too many requests" would leak existence.
 */
@Service
public class PasswordResetService {

    private static final String PURPOSE = "password-reset";
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisTokenStore tokenStore;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final Cooldown cooldown;
    private final VerificationLinkFactory linkFactory;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RedisTokenStore tokenStore,
            EmailSender emailSender,
            SmsSender smsSender,
            Cooldown cooldown,
            VerificationLinkFactory linkFactory) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.cooldown = cooldown;
        this.linkFactory = linkFactory;
    }

    /** Never throws — see class Javadoc. Silently does nothing for an unknown identifier or an active cooldown. */
    public void requestReset(Tenant tenant, String identifier) {
        String cooldownKey = PURPOSE + ":" + tenant.getId() + ":" + identifier.toLowerCase();
        if (cooldown.isActive(cooldownKey)) {
            return;
        }
        cooldown.start(cooldownKey, RESEND_COOLDOWN);

        Optional<User> found = IdentifierFormat.isValidEmail(identifier)
                ? userRepository.findByTenantAndEmail(tenant, identifier)
                : userRepository.findByTenantAndPhone(tenant, identifier);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();

        Duration ttl = Duration.ofSeconds(tenant.getPasswordResetTtlSeconds());
        String token = tokenStore.issue(PURPOSE, user.getId().toString(), ttl);
        String link = linkFactory.build("/ui/password-reset/confirm", token);

        // Prefer email when available; SMS only for phone-only accounts.
        if (user.getEmail() != null) {
            emailSender.send(user.getEmail(), "Reset your password", "<p>Click to reset your password: " + link + "</p>");
        } else if (user.getPhone() != null) {
            smsSender.send(user.getPhone(), "Reset your password: " + link);
        }
    }

    @Transactional
    public void confirmReset(String token, String newPassword) {
        String userId = tokenStore
                .consume(PURPOSE, token)
                .orElseThrow(() -> new InvalidTokenException("Password reset link is invalid or has expired"));

        User user = userRepository
                .findById(UUID.fromString(userId))
                .orElseThrow(() -> new InvalidTokenException("Password reset link is invalid or has expired"));

        PasswordPolicy.validate(newPassword);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
