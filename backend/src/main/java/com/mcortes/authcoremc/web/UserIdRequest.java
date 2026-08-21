package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Shared shape for the {@code otp/request} and {@code totp/enroll} endpoints. */
public record UserIdRequest(@NotNull UUID userId) {}
