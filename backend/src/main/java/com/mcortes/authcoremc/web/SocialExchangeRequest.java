package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/oauth2/social-exchange} (ticket 038) — the
 * one-time code {@code SocialLoginSuccessHandler} handed the browser via the
 * {@code /ui/social-callback?client_id=...&code=...} redirect.
 */
public record SocialExchangeRequest(@NotBlank String code) {}
