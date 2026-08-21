package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/**
 * email/phone are intentionally NOT annotated here (e.g. no {@code @Email})
 * — the "at least one, correct format" rule is a business rule owned by
 * {@link com.mcortes.authcoremc.service.RegistrationService}, so it's
 * enforced and tested in exactly one place instead of duplicated (and
 * potentially drifting) across the DTO and the service.
 */
public record RegisterRequest(
        String email, String phone, @NotBlank String nombre, @NotBlank String apellidos, @NotBlank String password) {}
