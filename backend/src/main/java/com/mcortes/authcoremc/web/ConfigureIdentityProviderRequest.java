package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

public record ConfigureIdentityProviderRequest(@NotBlank String clientId, @NotBlank String clientSecret) {}
