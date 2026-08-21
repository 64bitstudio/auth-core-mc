package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.VerificationLinkFactory;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.IdentifierFormat;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email change: the new address only takes effect once its confirmation
 * link is used — the old address stays active until then (docs/API.md).
 * The token's value packs both the acting user and the requested new
 * email, so confirmation doesn't have to trust anything the client sends
 * except the token itself.
 */
@Service
public class EmailChangeService {

    private static final String PURPOSE = "email-change";
    private static final String SEPARATOR = "::";

    private final UserRepository userRepository;
    private final RedisTokenStore tokenStore;
    private final EmailSender emailSender;
    private final VerificationLinkFactory linkFactory;

    public EmailChangeService(
            UserRepository userRepository,
            RedisTokenStore tokenStore,
            EmailSender emailSender,
            VerificationLinkFactory linkFactory) {
        this.userRepository = userRepository;
        this.tokenStore = tokenStore;
        this.emailSender = emailSender;
        this.linkFactory = linkFactory;
    }

    public void requestChange(User user, String newEmail) {
        if (!IdentifierFormat.isValidEmail(newEmail)) {
            throw new IllegalArgumentException("Invalid email format");
        }
        if (userRepository.findByTenantAndEmail(user.getTenant(), newEmail).isPresent()) {
            throw new DuplicateIdentifierException("Email is already registered for this tenant");
        }

        Duration ttl = Duration.ofSeconds(user.getTenant().getEmailVerificationTtlSeconds());
        String token = tokenStore.issue(PURPOSE, user.getId() + SEPARATOR + newEmail, ttl);

        String link = linkFactory.build("/api/v1/change-email/confirm", token);
        emailSender.send(newEmail, "Confirm your new email", "<p>Click to confirm your new email: " + link + "</p>");
    }

    @Transactional
    public void confirmChange(String token) {
        String value = tokenStore
                .consume(PURPOSE, token)
                .orElseThrow(() -> new InvalidTokenException("Email change link is invalid or has expired"));

        String[] parts = value.split(SEPARATOR, 2);
        UUID userId = UUID.fromString(parts[0]);
        String newEmail = parts[1];

        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new InvalidTokenException("Email change link is invalid or has expired"));

        // Re-check uniqueness: another user could have registered/changed to
        // this exact email during the window the link was outstanding.
        if (userRepository.findByTenantAndEmail(user.getTenant(), newEmail).isPresent()) {
            throw new DuplicateIdentifierException("Email is already registered for this tenant");
        }

        user.changeEmail(newEmail);
        userRepository.save(user);
    }
}
