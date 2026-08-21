package com.mcortes.authcoremc.security;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.domain.UserRole;
import java.util.UUID;
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
        return canAccessTenant(actor.getRole(), actor.getTenant().getId(), target.getId());
    }

    /**
     * Value-based overload (ticket 012) for the HTTP-layer guard, which only
     * has the role/tenant_id claims off a decoded JWT — no {@link User}/
     * {@link Tenant} entities, and deliberately no DB lookup per request to
     * get them. Same decision as {@link #canAccessTenant(User, Tenant)}, so
     * that method now delegates here instead of duplicating the logic.
     */
    public boolean canAccessTenant(UserRole role, UUID actorTenantId, UUID targetTenantId) {
        if (role == UserRole.PLATFORM_ADMIN) {
            return true;
        }
        if (role == UserRole.TENANT_ADMIN) {
            return actorTenantId != null && actorTenantId.equals(targetTenantId);
        }
        return false;
    }
}
