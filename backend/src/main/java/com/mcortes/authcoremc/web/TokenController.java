package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.TokenPair;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refresh/revoke for the direct-grant's refresh token (ticket 007). No
 * {@code X-Client-Id} needed here — the refresh token itself already
 * identifies which client/user it belongs to (see docs/BASE_DE_DATOS.md,
 * {@code refresh_token} table).
 */
@RestController
@RequestMapping("/api/v1/token")
public class TokenController {

    private final DirectTokenService directTokenService;

    public TokenController(DirectTokenService directTokenService) {
        this.directTokenService = directTokenService;
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenPair> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(directTokenService.refresh(request.refreshToken()));
    }

    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@Valid @RequestBody RefreshTokenRequest request) {
        directTokenService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
