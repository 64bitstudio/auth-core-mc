package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/** {@code identifier} is either an email or a phone — see docs/API.md. */
public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}
