package com.mcortes.authcoremc.web;

/** Ticket 013: a deactivated tenant blocks all new activity (login, registration, etc.), not just admin actions — see {@code ClientContextResolver}. */
public class TenantDeactivatedException extends RuntimeException {

    public TenantDeactivatedException() {
        super("This tenant has been deactivated");
    }
}
