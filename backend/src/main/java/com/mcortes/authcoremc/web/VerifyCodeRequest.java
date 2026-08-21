package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record VerifyCodeRequest(@NotNull UUID userId, @NotBlank String code) {}
