package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.UserNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads a user by id and verifies it belongs to the tenant resolved for
 * the request -- the one remaining caller (after ticket 071) is {@link
 * EmailVerificationController}, which accepts a client-supplied {@code
 * userId} deliberately and permanently, not as a stopgap anymore.
 *
 * <p>Originally (tickets 003/005) this backed {@code
 * EmailChangeController} and {@code TwoFactorController} too, as a
 * temporary trust boundary meant to be replaced once a real Authorization
 * Server existed (ticket 007). It was never migrated after 007 landed,
 * and ticket 071 (2026-09-15, real security finding) found that gap
 * genuinely exploitable for those two: {@code change-email} sends its
 * confirmation to whatever new address the CALLER chooses, and {@code
 * 2fa/totp/enroll} hands the secret straight back in the response without
 * involving any channel the account owner controls -- both migrated to
 * a real Bearer JWT.
 *
 * <p>{@code verify-email/request} stayed on this resolver, deliberately:
 * it only ever resends to the address ALREADY on the account, rate-limited
 * by its own cooldown -- guessing someone else's userId here is bounded
 * annoyance (an unwanted email), never a path to hijacking anything. See
 * {@link EmailVerificationController}'s Javadoc for the full reasoning,
 * including the real caller (galgoth-studio's post-registration
 * auto-send) that a Bearer-only requirement would have broken outright.
 */
@Component
public class TenantScopedUserResolver {

    private final UserRepository userRepository;

    public TenantScopedUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(Tenant tenant, UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        if (!user.getTenant().getId().equals(tenant.getId())) {
            throw new UserNotFoundException();
        }
        return user;
    }
}
