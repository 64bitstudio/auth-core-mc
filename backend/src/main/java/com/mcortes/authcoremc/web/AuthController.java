package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct (first-party) login. Returns the authenticated user, not a token —
 * token issuance is ticket 007's Authorization Server integration; see
 * AuthenticationService's Javadoc for why that boundary was drawn here.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final ClientContextResolver clientContextResolver;
    private final AuthenticationService authenticationService;

    public AuthController(ClientContextResolver clientContextResolver, AuthenticationService authenticationService) {
        this.clientContextResolver = clientContextResolver;
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody LoginRequest request) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        User user = authenticationService.authenticate(tenant, request.identifier(), request.password());
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
