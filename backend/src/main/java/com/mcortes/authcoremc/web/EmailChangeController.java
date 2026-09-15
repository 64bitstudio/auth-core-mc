package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.EmailChangeService;
import com.mcortes.authcoremc.service.UserNotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hallazgo real de seguridad (2026-09-15, encontrado al conectar login
 * social/2FA en galgoth-studio): {@code /request} confiaba un {@code
 * userId} mandado tal cual en el body, sin ninguna autenticación real
 * (mismo "trust boundary temporal" que {@link
 * com.mcortes.authcoremc.web.TenantScopedUserResolver} documentaba desde
 * el ticket 007, nunca migrado). Encadenado con que
 * {@code ProjectSummary.avatarUrl} de galgoth-studio expone el UUID real
 * del dueño en `/api/explore/projects` (público, sin sesión), cualquiera
 * podía pedir un cambio de correo hacia una dirección propia y
 * confirmarlo -- el correo REAL del dueño nunca recibe ningún aviso (ver
 * {@code EmailChangeService.requestChange}) -- resultando en secuestro
 * completo de cuenta vía reset de contraseña después. Corregido: el
 * usuario sale exclusivamente del JWT verificado ({@code jwt.getSubject()}),
 * igual que {@link AccountProfileController}/{@link SetPasswordController}
 * -- nunca del body. {@code /confirm} no cambia: ya usa el token emailed
 * como única credencial, ese paso siempre fue correcto.
 */
@RestController
@RequestMapping("/api/v1/change-email")
public class EmailChangeController {

    private final ClientContextResolver clientContextResolver;
    private final UserRepository userRepository;
    private final EmailChangeService emailChangeService;

    public EmailChangeController(
            ClientContextResolver clientContextResolver,
            UserRepository userRepository,
            EmailChangeService emailChangeService) {
        this.clientContextResolver = clientContextResolver;
        this.userRepository = userRepository;
        this.emailChangeService = emailChangeService;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RequestEmailChangeRequest request) {
        User user = userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow(UserNotFoundException::new);
        IdentityClient client = clientContextResolver.resolveClient(JwtAudience.firstClientId(jwt));
        emailChangeService.requestChange(user, request.newEmail(), client);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmTokenRequest request) {
        emailChangeService.confirmChange(request.token());
        return ResponseEntity.ok().build();
    }
}
