package com.mcortes.authcoremc.web;

/**
 * Ticket 018: thrown for every break-glass authentication failure — wrong
 * shared secret, wrong TOTP code, disallowed IP, or the mechanism not
 * being configured at all. Deliberately generic on purpose: the HTTP
 * response never says WHICH check failed (that would let an attacker who
 * has some but not all factors narrow down what's still missing) — the
 * specific reason is only ever recorded server-side, in the audit trail
 * ({@code BreakGlassAuditEvent.detail}).
 */
public class BreakGlassAuthenticationException extends RuntimeException {

    public BreakGlassAuthenticationException() {
        super("Break-glass authentication failed");
    }
}
