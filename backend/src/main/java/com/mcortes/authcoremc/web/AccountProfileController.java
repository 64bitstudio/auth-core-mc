package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.AccountProfileService;
import com.mcortes.authcoremc.service.UserNotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 060 ("Mi Perfil", galgoth-studio) -- lee/edita nombre, apellidos,
 * país y nombre de usuario del usuario autenticado. El correo NO se edita
 * acá (sigue el flujo de {@link EmailChangeController}, ver el documento
 * de definición §1). No está en el {@code permitAll} de {@code
 * SecurityConfig} -- cae bajo {@code anyRequest().authenticated()} igual
 * que {@link SetPasswordController}.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountProfileController {

    private final AccountProfileService accountProfileService;
    private final UserRepository userRepository;

    public AccountProfileController(AccountProfileService accountProfileService, UserRepository userRepository) {
        this.accountProfileService = accountProfileService;
        this.userRepository = userRepository;
    }

    @GetMapping("/profile")
    public UserResponse getProfile(@AuthenticationPrincipal Jwt jwt) {
        User user = userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow(UserNotFoundException::new);
        return UserResponse.from(user);
    }

    @PatchMapping("/profile")
    public ResponseEntity<UserResponse> updateProfile(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        User user = accountProfileService.updateProfile(
                UUID.fromString(jwt.getSubject()), request.nombre(), request.apellidos(), request.country(), request.username());
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
