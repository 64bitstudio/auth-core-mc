package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/** Ticket 061 -- {@code newPassword} sin anotación de fuerza aquí (misma razón que {@link SetPasswordRequest}: la política vive solo en {@code PasswordPolicy}). */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
