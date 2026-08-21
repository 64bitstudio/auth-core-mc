package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.AuthenticationService;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.InvalidCredentialsException;
import com.mcortes.authcoremc.service.LoginEventRecorder;
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
 *
 * <p>Ticket 015: every attempt through this endpoint is recorded via
 * {@link LoginEventRecorder} (provider {@code "PASSWORD"} — social login's
 * own callback isn't wired yet, see ticket 006's deferred note; it'll
 * record its own provider once it lands). The NotFirstPartyClientException
 * path is deliberately NOT recorded — it's not a real login attempt by an
 * actual user, it's a misconfigured/malicious client.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final String PASSWORD_PROVIDER = "PASSWORD";

    private final ClientContextResolver clientContextResolver;
    private final AuthenticationService authenticationService;
    private final DirectTokenService directTokenService;
    private final LoginEventRecorder loginEventRecorder;

    public AuthController(
            ClientContextResolver clientContextResolver,
            AuthenticationService authenticationService,
            DirectTokenService directTokenService,
            LoginEventRecorder loginEventRecorder) {
        this.clientContextResolver = clientContextResolver;
        this.authenticationService = authenticationService;
        this.directTokenService = directTokenService;
        this.loginEventRecorder = loginEventRecorder;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody LoginRequest request) {
        IdentityClient client = clientContextResolver.resolveClient(clientId);
        if (!client.isFirstParty()) {
            throw new NotFirstPartyClientException();
        }
        Tenant tenant = client.getTenant();

        long startedAt = System.currentTimeMillis();
        User user;
        try {
            user = authenticationService.authenticate(tenant, request.identifier(), request.password());
        } catch (InvalidCredentialsException e) {
            loginEventRecorder.recordFailure(tenant, PASSWORD_PROVIDER, System.currentTimeMillis() - startedAt);
            throw e;
        }
        loginEventRecorder.recordSuccess(tenant, user, PASSWORD_PROVIDER, System.currentTimeMillis() - startedAt);

        TokenPair tokens = directTokenService.issueTokens(client, user);
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(user), tokens));
    }
}
