package com.mcortes.authcoremc.web;

/** Ticket 013: a tenant_admin tried to act on a tenant that isn't their own — see {@code AdminAccessPolicy}. */
public class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException() {
        super("You do not have access to this tenant");
    }
}
