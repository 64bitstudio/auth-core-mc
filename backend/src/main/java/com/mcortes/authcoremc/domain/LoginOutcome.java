package com.mcortes.authcoremc.domain;

/** Result of a single login attempt (ticket 015) — feeds the admin panel's usage metrics (ticket 016). */
public enum LoginOutcome {
    SUCCESS,
    FAILURE
}
