package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.User;
import java.util.UUID;

/** Deliberately excludes password_hash and totp_secret_encrypted — never serialize secrets. */
public record UserResponse(
        UUID id, String email, String phone, String nombre, String apellidos, boolean emailVerified, boolean phoneVerified) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getNombre(),
                user.getApellidos(),
                user.isEmailVerified(),
                user.isPhoneVerified());
    }
}
