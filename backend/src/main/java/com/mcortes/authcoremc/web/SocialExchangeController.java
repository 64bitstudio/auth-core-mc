package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.NotFirstPartyClientException;
import com.mcortes.authcoremc.service.TokenPair;
import jakarta.validation.Valid;
import java.util.UUID;
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
 * {@code userId} (ver {@code RedisTokenStore.issue} allá) — {@link
 * DirectTokenService#issueTokens} necesita además un {@link IdentityClient}
 * first-party para mintear (mismos scopes/TTLs que {@code /login}). El
 * mismo {@code client_id} viaja en la URL del redirect a {@code
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
 */
@RestController
@RequestMapping("/api/v1/oauth2")
public class SocialExchangeController {

    private final ClientContextResolver clientContextResolver;
    private final RedisTokenStore redisTokenStore;
    private final UserRepository userRepository;
    private final DirectTokenService directTokenService;

    public SocialExchangeController(
            ClientContextResolver clientContextResolver,
            RedisTokenStore redisTokenStore,
            UserRepository userRepository,
            DirectTokenService directTokenService) {
        this.clientContextResolver = clientContextResolver;
        this.redisTokenStore = redisTokenStore;
        this.userRepository = userRepository;
        this.directTokenService = directTokenService;
    }

    @PostMapping("/social-exchange")
    public ResponseEntity<LoginResponse> exchange(
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

        TokenPair tokens = directTokenService.issueTokens(client, user);
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(user), tokens));
    }

    private static InvalidTokenException invalidCode() {
        return new InvalidTokenException("The exchange code is invalid, expired, or already used");
    }
}
