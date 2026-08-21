package com.mcortes.authcoremc.web;

/** Ticket 013: tenant.name has a real UNIQUE constraint — this is the clear, expected error instead of a raw constraint-violation leak. */
public class DuplicateTenantNameException extends RuntimeException {

    public DuplicateTenantNameException(String name) {
        super("A tenant named '" + name + "' already exists");
    }
}
