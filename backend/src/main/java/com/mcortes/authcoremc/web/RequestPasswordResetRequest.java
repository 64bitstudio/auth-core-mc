package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

public record RequestPasswordResetRequest(@NotBlank String identifier) {}
