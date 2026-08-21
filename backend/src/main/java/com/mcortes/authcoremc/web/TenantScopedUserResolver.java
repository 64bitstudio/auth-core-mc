package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.UserNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads a user by id and verifies it belongs to the tenant resolved for the
 * request — used by every endpoint that (until ticket 007 adds real
 * bearer-token authentication) has no session/principal to read "the
 * current user" from, and so has to accept a client-supplied userId
 * instead.
 *
 * <p>THIS IS A DELIBERATE, TEMPORARY TRUST BOUNDARY, not an oversight: a
 * caller who knows (or guesses) someone else's userId can trigger a
 * verification/change-email *request* for them — annoying (email spam,
 * bounded by {@link com.mcortes.authcoremc.security.Cooldown}) but not
 * exploitable, because completing any of these flows still requires
 * possessing the token sent to an inbox the caller doesn't control. Ticket
 * 007 replaces the client-supplied userId with the authenticated principal
 * from a real access token, closing even that annoyance-level gap.
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
