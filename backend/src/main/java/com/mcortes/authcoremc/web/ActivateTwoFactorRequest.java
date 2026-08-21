package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.TwoFactorMethod;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ActivateTwoFactorRequest(@NotNull UUID userId, @NotNull TwoFactorMethod method) {}
