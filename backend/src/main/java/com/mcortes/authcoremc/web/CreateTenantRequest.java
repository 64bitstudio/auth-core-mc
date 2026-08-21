package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Ticket 013: {@code name} is this tenant's stable, unique identity in the panel — never editable afterward, see {@code Tenant#update}. */
public record CreateTenantRequest(
        @NotBlank String name,
        @NotBlank String appName,
        @NotBlank String primaryColor,
        @Positive int accessTokenTtlSeconds,
        @Positive int refreshTokenTtlSeconds,
        @Positive int emailVerificationTtlSeconds,
        @Positive int passwordResetTtlSeconds,
        @Positive int otpTtlSeconds) {}
