package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPasswordResetRequest(@NotBlank String token, @NotBlank String newPassword) {}
