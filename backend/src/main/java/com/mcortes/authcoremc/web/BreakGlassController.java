package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.service.BreakGlassService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 018: emergency admin access. {@code permitAll()} in {@code
 * SecurityConfig} — deliberately NOT gated by the JWT/role machinery the
 * rest of {@code /api/v1/admin/**} uses, since the whole point is to keep
 * working if that machinery is what's broken. Authorization instead
 * happens entirely inside {@link BreakGlassService} (secret + TOTP + IP
 * allowlist), and every call — success or failure — is audited there.
 *
 * <p>{@code POST}, not {@code GET}, for diagnostics too: both endpoints
 * need a request body (the three auth factors), and a body on a GET is
 * unreliable across HTTP clients/proxies.
 */
@RestController
@RequestMapping("/api/v1/breakglass")
public class BreakGlassController {

    private final BreakGlassService service;

    public BreakGlassController(BreakGlassService service) {
        this.service = service;
    }

    @PostMapping("/diagnostics")
    public ResponseEntity<BreakGlassDiagnosticsResponse> diagnostics(
            @Valid @RequestBody BreakGlassAuthRequest request, HttpServletRequest httpRequest) {
        BreakGlassDiagnosticsResponse response =
                service.diagnostics(request.secret(), request.totpCode(), request.operator(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tenants/{id}/deactivate")
    public ResponseEntity<Void> deactivateTenant(
            @PathVariable UUID id, @Valid @RequestBody BreakGlassAuthRequest request, HttpServletRequest httpRequest) {
        service.deactivateTenant(
                id, request.secret(), request.totpCode(), request.operator(), httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
