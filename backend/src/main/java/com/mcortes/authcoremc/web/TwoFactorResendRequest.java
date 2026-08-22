package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/login/2fa-resend} (ticket 046) — see {@code TwoFactorLoginController}. */
public record TwoFactorResendRequest(@NotBlank String pendingToken) {}
