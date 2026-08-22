package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/**
 * newPassword is intentionally NOT annotated with a length/pattern
 * constraint here — the actual strength rule (min. 8 chars, letter+digit)
 * is owned by {@link com.mcortes.authcoremc.security.PasswordPolicy}, same
 * as {@code RegisterRequest}, so it's enforced/tested in exactly one place.
 */
public record SetPasswordRequest(@NotBlank String newPassword) {}
