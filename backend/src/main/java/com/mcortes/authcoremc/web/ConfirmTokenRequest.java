package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

public record ConfirmTokenRequest(@NotBlank String token) {}
