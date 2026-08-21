package com.mcortes.authcoremc.domain;

/** What a break-glass call attempted (ticket 018) — feeds {@link BreakGlassAuditEvent}. */
public enum BreakGlassAction {
    DIAGNOSTICS,
    DEACTIVATE_TENANT
}
