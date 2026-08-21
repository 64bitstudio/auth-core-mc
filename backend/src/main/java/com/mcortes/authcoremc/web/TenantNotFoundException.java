package com.mcortes.authcoremc.web;

import java.util.UUID;

/** Ticket 013: no tenant exists with the given id. */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(UUID tenantId) {
        super("No tenant found with id " + tenantId);
    }
}
