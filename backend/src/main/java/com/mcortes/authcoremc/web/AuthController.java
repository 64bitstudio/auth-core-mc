package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.AuthenticationService;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.NotFirstPartyClientException;
import com.mcortes.authcoremc.service.TokenPair;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct (first-party) login — now mints a real, correctly-signed access
 * token + refresh token via {@link DirectTokenService} (ticket 007). The
 * first-party check happens here, before ever calling
 * {@link AuthenticationService}, so a third-party client attempting this
 * grant is rejected without even touching credential verification/rate
 * limiting for a grant it was never allowed to use.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final ClientContextResolver clientContextResolver;
    private final AuthenticationService authenticationService;
    private final DirectTokenService directTokenService;

    public AuthController(
            ClientContextResolver clientContextResolver,
            AuthenticationService authenticationService,
            DirectTokenService directTokenService) {
        this.clientContextResolver = clientContextResolver;
        this.authenticationService = authenticationService;
        this.directTokenService = directTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody LoginRequest request) {
        IdentityClient client = clientContextResolver.resolveClient(clientId);
        if (!client.isFirstParty()) {
            throw new NotFirstPartyClientException();
        }

        User user = authenticationService.authenticate(client.getTenant(), request.identifier(), request.password());
        TokenPair tokens = directTokenService.issueTokens(client, user);
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(user), tokens));
    }
}
