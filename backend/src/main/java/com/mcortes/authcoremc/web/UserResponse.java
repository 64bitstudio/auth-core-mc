package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.User;
import java.util.UUID;

/**
 * Deliberately excludes password_hash and totp_secret_encrypted — never
 * serialize secrets. {@code hasPassword} (ticket 041, HU-5) is a boolean
 * derived from {@code password_hash != null} — safe to expose, and what
 * `/ui/cuenta` uses to decide whether to offer "Establecer contraseña"
 * (only for a social-only account) instead of leaking the hash itself.
 */
public record UserResponse(
        UUID id,
        String email,
        String phone,
        String nombre,
        String apellidos,
        boolean emailVerified,
        boolean phoneVerified,
        boolean hasPassword) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getNombre(),
                user.getApellidos(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getPasswordHash() != null);
    }
}
