package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.AuthenticationService;
import com.mcortes.authcoremc.service.InvalidCredentialsException;
import com.mcortes.authcoremc.service.LoginCompletionResult;
import com.mcortes.authcoremc.service.LoginCompletionService;
import com.mcortes.authcoremc.service.LoginEventRecorder;
import com.mcortes.authcoremc.service.NotFirstPartyClientException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct (first-party) login. The first-party check happens here, before
 * ever calling {@link AuthenticationService}, so a third-party client
 * attempting this grant is rejected without even touching credential
 * verification/rate limiting for a grant it was never allowed to use.
 *
 * <p>Ticket 015: every attempt through this endpoint is recorded via
 * {@link LoginEventRecorder} (provider {@code "PASSWORD"}). The
 * NotFirstPartyClientException path is deliberately NOT recorded — it's
 * not a real login attempt by an actual user, it's a misconfigured/
 * malicious client.
 *
 * <p><b>Ticket 045 — where "success" is recorded, unchanged by the 2FA
 * gate:</b> {@code recordSuccess} fires right after the password itself is
 * proven correct, exactly like before this ticket — it does NOT wait for
 * {@link LoginCompletionService} to decide whether tokens are minted right
 * away or a second factor is still pending. Rationale: (1) {@code
 * LoginOutcome} is a two-value DB-level {@code CHECK} constraint
 * (SUCCESS/FAILURE, see {@code V5__login_event.sql}) — adding a third
 * "pending 2FA" state is a schema change needing its own dedicated VoBo,
 * not something to slip into this ticket; (2) this mirrors the precedent
 * {@code SocialLoginSuccessHandler} already set for the exact same
 * multi-step-login tension — it records success at "identity proven",
 * before the separate, possibly-failing token-exchange step. Password login
 * now follows the same "success" semantics: identity proven, not
 * necessarily a session actually granted yet. A wrong/abandoned second
 * factor is consequently NOT recorded as a new event at all — flagged as a
 * real gap (not a silent decision) in the ticket 045 report: {@code
 * LoginEventRecorder}/{@code LoginOutcome} would need a third state to
 * properly attribute that outcome, which is its own schema-change ticket.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final String PASSWORD_PROVIDER = "PASSWORD";

    private final ClientContextResolver clientContextResolver;
    private final AuthenticationService authenticationService;
    private final LoginCompletionService loginCompletionService;
    private final LoginEventRecorder loginEventRecorder;

    public AuthController(
            ClientContextResolver clientContextResolver,
            AuthenticationService authenticationService,
            LoginCompletionService loginCompletionService,
            LoginEventRecorder loginEventRecorder) {
        this.clientContextResolver = clientContextResolver;
        this.authenticationService = authenticationService;
        this.loginCompletionService = loginCompletionService;
        this.loginEventRecorder = loginEventRecorder;
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(
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

        LoginCompletionResult result = loginCompletionService.complete(client, user);
        if (result.twoFactorRequired()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new TwoFactorRequiredResponse(result.pendingToken(), result.method()));
        }
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(result.user()), result.tokens()));
    }
}
