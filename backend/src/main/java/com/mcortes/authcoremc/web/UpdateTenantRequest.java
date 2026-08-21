package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Ticket 013: {@code name} deliberately absent — see {@code Tenant#update}. */
public record UpdateTenantRequest(
        @NotBlank String appName,
        @NotBlank String primaryColor,
        @Positive int accessTokenTtlSeconds,
        @Positive int refreshTokenTtlSeconds,
        @Positive int emailVerificationTtlSeconds,
        @Positive int passwordResetTtlSeconds,
        @Positive int otpTtlSeconds) {}
