package com.mcortes.authcoremc.domain;

/** Result of a single break-glass call (ticket 018) — a FAILURE row includes WHY it failed as auditable detail. */
public enum BreakGlassOutcome {
    SUCCESS,
    FAILURE
}
