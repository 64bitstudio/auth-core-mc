package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Ticket 018: the three factors + accountability field every break-glass
 * call requires. {@code operator} is free text (there's no real login
 * here to derive an identity from) — the strength of this door is the
 * secret+TOTP+IP combination; {@code operator} exists purely so the audit
 * trail says who claimed to be acting, same non-repudiation shape as
 * "sudo logs the username you typed," not a cryptographic identity proof.
 */
public record BreakGlassAuthRequest(
        @NotBlank String secret, @NotBlank String totpCode, @NotBlank String operator) {}
