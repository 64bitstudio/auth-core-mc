package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/login/2fa-verify} (ticket 045) — see {@code TwoFactorLoginController}. */
public record TwoFactorVerifyRequest(@NotBlank String pendingToken, @NotBlank String code) {}
