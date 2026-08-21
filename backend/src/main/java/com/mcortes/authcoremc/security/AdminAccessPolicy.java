package com.mcortes.authcoremc.security;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.domain.UserRole;
import org.springframework.stereotype.Component;

/**
 * Ticket 011: decides whether a user may administer a given tenant through
 * the admin panel. Pure decision logic, deliberately independent of the
 * HTTP layer — ticket 012 wires this into an actual request-guard/filter
 * against the authenticated principal; this class only answers the
 * yes/no question, so it can be unit-tested without a servlet context.
 *
 * <p>Compares tenants by id, not by object identity — {@link Tenant} does
 * not override {@code equals}, and two references can represent the same
 * persisted row (e.g. one loaded from the security context, one loaded
 * fresh from a repository for the request being authorized).
 */
@Component
public class AdminAccessPolicy {

    public boolean canAccessTenant(User actor, Tenant target) {
        if (actor.getRole() == UserRole.PLATFORM_ADMIN) {
            return true;
        }
        if (actor.getRole() == UserRole.TENANT_ADMIN) {
            return actor.getTenant().getId().equals(target.getId());
        }
        return false;
    }
}
