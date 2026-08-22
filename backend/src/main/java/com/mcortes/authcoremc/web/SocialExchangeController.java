package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.LoginCompletionResult;
import com.mcortes.authcoremc.service.LoginCompletionService;
import com.mcortes.authcoremc.service.NotFirstPartyClientException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Last step of the "Login social exitoso" sequence in
 * docs/definiciones/login-social-real.md (Diseño técnico, decisión 5):
 * canjea el código de un solo uso que {@link SocialLoginSuccessHandler}
 * emitió por tokens reales, vía el mismo minter que {@link AuthController}
 * ya usa para {@code /api/v1/login} — nunca un token/código en la URL de
 * redirect, solo aquí, tras validar la única credencial real de este
 * endpoint (el código).
 *
 * <p><b>Por qué requiere {@code X-Client-Id} igual que {@code /login}</b>:
 * el código emitido por {@code SocialLoginSuccessHandler} solo lleva el
 * {@code userId} (ver {@code RedisTokenStore.issue} allá) — el minter final
 * ({@code DirectTokenService}, vía {@link LoginCompletionService}) necesita
 * además un {@link IdentityClient} first-party para mintear (mismos
 * scopes/TTLs que {@code /login}). El mismo {@code client_id} viaja en la
 * URL del redirect a {@code
 * /ui/social-callback} (ver ese handler), y {@code AuthCoreUi.call(...)} ya
 * adjunta automáticamente ese {@code client_id} como header {@code
 * X-Client-Id} en cada llamada — el mismo mecanismo que usa cada otro
 * endpoint de este proyecto, ninguno nuevo.
 *
 * <p><b>Verificación cruzada de tenant</b>: el código en sí no prueba a qué
 * tenant pertenece el usuario resuelto — solo el {@code userId}. Este
 * endpoint confirma que el tenant del usuario resuelto coincide con el
 * tenant del cliente resuelto por {@code X-Client-Id} antes de mintear
 * nada; un desajuste (p. ej. un código robado de otro tenant combinado con
 * un {@code client_id} distinto) falla igual de genérico que un código
 * inválido — nunca revela cuál de las dos cosas no coincidió.
 *
 * <p><b>Ticket 045 — 2FA:</b> igual que {@code AuthController}, esta vez
 * llama a {@link LoginCompletionService#complete} en vez de mintear tokens
 * directamente — mismo punto de unificación, ningún gate paralelo. Si el
 * usuario resuelto tiene 2FA activo, responde {@code 202} con {@code
 * TwoFactorRequiredResponse} en vez de tokens; el cliente completa el login
 * vía {@code POST /api/v1/login/2fa-verify}, el mismo endpoint compartido
 * que usa el flujo de password. No se agrega ningún registro nuevo en
 * {@code LoginEventRecorder} aquí — este controller nunca lo llamó (el
 * evento de login social ya se registra antes, en {@code
 * SocialLoginSuccessHandler}, sin cambios por este ticket).
 */
@RestController
@RequestMapping("/api/v1/oauth2")
public class SocialExchangeController {

    private final ClientContextResolver clientContextResolver;
    private final RedisTokenStore redisTokenStore;
    private final UserRepository userRepository;
    private final LoginCompletionService loginCompletionService;

    public SocialExchangeController(
            ClientContextResolver clientContextResolver,
            RedisTokenStore redisTokenStore,
            UserRepository userRepository,
            LoginCompletionService loginCompletionService) {
        this.clientContextResolver = clientContextResolver;
        this.redisTokenStore = redisTokenStore;
        this.userRepository = userRepository;
        this.loginCompletionService = loginCompletionService;
    }

    @PostMapping("/social-exchange")
    public ResponseEntity<?> exchange(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody SocialExchangeRequest request) {
        IdentityClient client = clientContextResolver.resolveClient(clientId);
        if (!client.isFirstParty()) {
            // Checked before consuming the code (not left to DirectTokenService's
            // own check) so a wrong X-Client-Id doesn't burn an otherwise-valid,
            // single-use code — same ordering AuthController uses for /login.
            throw new NotFirstPartyClientException();
        }

        String userId = redisTokenStore
                .consume(SocialLoginSuccessHandler.EXCHANGE_PURPOSE, request.code())
                .orElseThrow(SocialExchangeController::invalidCode);

        User user = userRepository.findById(UUID.fromString(userId)).orElseThrow(SocialExchangeController::invalidCode);

        if (!user.getTenant().getId().equals(client.getTenant().getId())) {
            // Never surfaced in practice by the real flow (the same
            // IdentityClient resolves both the redirect and this exchange) —
            // a defensive cross-tenant check, deliberately as generic as an
            // unknown/expired code.
            throw invalidCode();
        }

        LoginCompletionResult result = loginCompletionService.complete(client, user);
        if (result.twoFactorRequired()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new TwoFactorRequiredResponse(result.pendingToken(), result.method()));
        }
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(result.user()), result.tokens()));
    }

    private static InvalidTokenException invalidCode() {
        return new InvalidTokenException("The exchange code is invalid, expired, or already used");
    }
}
