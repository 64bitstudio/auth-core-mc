package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.ChangePasswordService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 061 ("Mi Perfil", galgoth-studio) -- cambia la contraseña de un
 * usuario autenticado CON contraseña, confirmando la actual. Distinto de
 * {@link SetPasswordController} (primera vez, cuentas social-only) y del
 * flujo de "olvidé mi contraseña" ({@link PasswordResetController}, sin
 * sesión). Mismo mecanismo de auth que ambos: Bearer real, `userId` del
 * `sub` del JWT.
 */
@RestController
@RequestMapping("/api/v1/account")
public class ChangePasswordController {

    private final ChangePasswordService changePasswordService;

    public ChangePasswordController(ChangePasswordService changePasswordService) {
        this.changePasswordService = changePasswordService;
    }

    @PatchMapping("/password")
    public ResponseEntity<UserResponse> changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        User user = changePasswordService.changePassword(
                UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
