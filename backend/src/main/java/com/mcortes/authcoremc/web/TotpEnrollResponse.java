package com.mcortes.authcoremc.web;

/** {@code secret} is shown once (QR/manual entry) — never persisted or returned again in plain text. */
public record TotpEnrollResponse(String secret) {}
