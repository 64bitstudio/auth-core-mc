package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.service.AccountSessionsService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 062 ("Sesiones activas", Mi Perfil de galgoth-studio) — mismo
 * mecanismo de auth que {@link AccountProfileController}. El header
 * opcional {@code X-Current-Refresh-Token} (el refresh token crudo que el
 * caller ya tiene guardado) es lo único que permite marcar cuál sesión es
 * "Actual" — el JWT de acceso no lleva ninguna referencia a qué refresh
 * token lo emitió (ver Javadoc de {@code AccountSessionsService}). Sin ese
 * header, la lista se devuelve igual, solo sin ninguna marcada como
 * actual.
 */
@RestController
@RequestMapping("/api/v1/account/sessions")
public class AccountSessionsController {

    private final AccountSessionsService accountSessionsService;

    public AccountSessionsController(AccountSessionsService accountSessionsService) {
        this.accountSessionsService = accountSessionsService;
    }

    @GetMapping
    public List<SessionSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Current-Refresh-Token", required = false) String currentRefreshToken) {
        return accountSessionsService.list(UUID.fromString(jwt.getSubject()), currentRefreshToken);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        accountSessionsService.revoke(UUID.fromString(jwt.getSubject()), sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/revoke-others")
    public ResponseEntity<Void> revokeOthers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Current-Refresh-Token", required = false) String currentRefreshToken) {
        accountSessionsService.revokeOthers(UUID.fromString(jwt.getSubject()), currentRefreshToken);
        return ResponseEntity.noContent().build();
    }
}
