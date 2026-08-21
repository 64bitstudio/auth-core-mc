package com.mcortes.authcoremc.domain;

/**
 * Administrative role for the admin panel (ticket 011). {@code NONE} is the
 * default for every regular end user of a tenant — most rows in
 * {@code app_user} are not panel admins at all.
 */
public enum UserRole {
    NONE,
    TENANT_ADMIN,
    PLATFORM_ADMIN
}
