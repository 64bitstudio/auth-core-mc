package com.mcortes.authcoremc.service;

/**
 * Thrown when a registration's email or phone already exists for the same
 * tenant. Translated from the database's own UNIQUE constraints
 * (app_user_tenant_email_unique / app_user_tenant_phone_unique, see
 * docs/BASE_DE_DATOS.md) — the constraint is the real source of truth (it
 * also protects against races between two concurrent registrations), this
 * exception just gives callers a clean domain-level signal instead of a raw
 * persistence exception leaking out of the service layer.
 */
public class DuplicateIdentifierException extends RuntimeException {

    public DuplicateIdentifierException(String message) {
        super(message);
    }
}
