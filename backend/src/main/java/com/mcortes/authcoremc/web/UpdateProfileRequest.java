package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Ticket 060 -- {@code country}/{@code username} son opcionales (null o
 * blank los borra, ver {@code User.updateProfile}); nombre/apellidos ya son
 * obligatorios en el dominio.
 */
public record UpdateProfileRequest(@NotBlank String nombre, @NotBlank String apellidos, String country, String username) {}
