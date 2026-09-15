package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SimpleAuthorizationServerContext;
import com.mcortes.authcoremc.oauth2.TenantAwareRegisteredClientRepository;
import com.mcortes.authcoremc.repository.RefreshTokenRepository;
import com.mcortes.authcoremc.security.TokenHasher;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints a real, correctly-signed OAuth2 access token (JWT) plus a refresh
 * token for the first-party direct login grant (docs/ARQUITECTURA.md
 * decision 3) — the one AuthenticationService (ticket 002) deliberately
 * stopped short of, since this piece (real Spring Authorization Server
 * token generation) didn't exist until this ticket.
 *
 * <p>The access token uses Spring's own {@link JwtGenerator} — the exact
 * same code path {@code /oauth2/token} uses internally — called directly
 * with a manually-built {@link OAuth2TokenContext}, since there's no HTTP
 * request going through the {@code /oauth2/**} filter chain here (see
 * {@link SimpleAuthorizationServerContext}'s Javadoc for why that context
 * has to be set by hand).
 *
 * <p>The refresh token is deliberately NOT a JWT — it's a plain random
 * opaque string, hashed (SHA-256, see {@link TokenHasher}) and stored in
 * our own {@code refresh_token} table (built in ticket 001 for exactly
 * this). Revoking it is a direct, synchronous DB check on redemption — see
 * {@link #refresh} — correct and immediate without needing a separate
 * Redis layer (a deliberate simplification from the ticket's original
 * "vía Redis" phrasing: a refresh exchange isn't hot enough a path to need
 * that, and a single DB row is one less place for state to drift).
 */
@Service
public class DirectTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AuthorizationGrantType DIRECT_GRANT =
            new AuthorizationGrantType("urn:mcortes:params:oauth:grant-type:direct");

    private final TenantAwareRegisteredClientRepository registeredClientRepository;
    private final JwtGenerator jwtGenerator;
    private final AuthorizationServerSettings authorizationServerSettings;
    private final RefreshTokenRepository refreshTokenRepository;

    public DirectTokenService(
            TenantAwareRegisteredClientRepository registeredClientRepository,
            JwtGenerator jwtGenerator,
            AuthorizationServerSettings authorizationServerSettings,
            RefreshTokenRepository refreshTokenRepository) {
        this.registeredClientRepository = registeredClientRepository;
        this.jwtGenerator = jwtGenerator;
        this.authorizationServerSettings = authorizationServerSettings;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Sin `userAgent`/`clientIp` -- preserva el comportamiento histórico para callers/tests que no los tienen a mano (ver {@link #issueTokens(IdentityClient, User, String, String)}). */
    @Transactional
    public TokenPair issueTokens(IdentityClient client, User user) {
        return doIssueTokens(client, user, null, null);
    }

    /** Ticket 062 -- {@code userAgent} (header `User-Agent` del request real de login/2FA-verify, nullable) queda registrado en `refresh_token` para "Sesiones activas". Sin `clientIp` -- preserva el comportamiento histórico para callers/tests de antes del ticket 070. */
    @Transactional
    public TokenPair issueTokens(IdentityClient client, User user, String userAgent) {
        return doIssueTokens(client, user, userAgent, null);
    }

    /** Ticket 070 -- {@code clientIp} (nullable, {@code request.getRemoteAddr()} del request real de login/2FA-verify) queda registrado en `refresh_token` para geolocalizar "Sesiones activas". */
    @Transactional
    public TokenPair issueTokens(IdentityClient client, User user, String userAgent, String clientIp) {
        return doIssueTokens(client, user, userAgent, clientIp);
    }

    /**
     * Cuerpo real compartido por ambos overloads -- deliberadamente sin
     * {@code @Transactional} propio: un método privado no pasa por el
     * proxy de Spring de todas formas, y evita el auto-invocación entre
     * métodos {@code @Transactional} públicos de la misma clase (java:S6809)
     * que tener uno llamar al otro vía {@code this} causaría.
     *
     * <p>Ticket 064 -- único punto real donde cualquier camino que emite un
     * token nuevo converge (login directo y social vía {@code
     * LoginCompletionService}, verificación de 2FA vía {@code
     * TwoFactorLoginController}) -- mismo criterio de "un solo punto de
     * decisión" que {@code ClientContextResolver} ya usa para tenants
     * desactivados. {@code refresh()} de abajo NO repite este chequeo a
     * propósito: eliminar la cuenta ya revoca todos los refresh tokens
     * (ver {@code AccountDeletionService}), así que un intento de refresh
     * posterior falla igual por "token inválido/revocado", sin necesidad
     * de duplicar la regla.
     */
    private TokenPair doIssueTokens(IdentityClient client, User user, String userAgent, String clientIp) {
        if (!client.isFirstParty()) {
            throw new NotFirstPartyClientException();
        }
        if (!user.isActive()) {
            throw new UserDeactivatedException();
        }

        RegisteredClient registeredClient = requireRegisteredClient(client.getClientId());
        Jwt accessToken = generateAccessToken(registeredClient, user);

        String rawRefreshToken = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusSeconds(
                registeredClient.getTokenSettings().getRefreshTokenTimeToLive().getSeconds());
        refreshTokenRepository.save(
                new RefreshToken(user, client, TokenHasher.sha256(rawRefreshToken), expiresAt, userAgent, clientIp));

        long expiresInSeconds = registeredClient.getTokenSettings().getAccessTokenTimeToLive().getSeconds();
        return new TokenPair(accessToken.getTokenValue(), rawRefreshToken, "Bearer", expiresInSeconds);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256(rawRefreshToken))
                .filter(token -> !token.isRevoked())
                .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid, expired, or revoked"));

        RegisteredClient registeredClient = requireRegisteredClient(stored.getClient().getClientId());
        Jwt accessToken = generateAccessToken(registeredClient, stored.getUser());
        long expiresInSeconds = registeredClient.getTokenSettings().getAccessTokenTimeToLive().getSeconds();

        // Ticket 062 -- "última actividad" de esta sesión para "Sesiones activas".
        stored.touch();
        refreshTokenRepository.save(stored);

        // The refresh token itself is not rotated (TokenSettings.reuseRefreshTokens
        // is effectively true for this direct-grant path) — a simplification;
        // rotating it is a natural future hardening step.
        return new TokenPair(accessToken.getTokenValue(), rawRefreshToken, "Bearer", expiresInSeconds);
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawRefreshToken)).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }

    private Jwt generateAccessToken(RegisteredClient registeredClient, User user) {
        AuthorizationServerContextHolder.setContext(new SimpleAuthorizationServerContext(authorizationServerSettings));
        try {
            OAuth2TokenContext context = DefaultOAuth2TokenContext.builder()
                    .registeredClient(registeredClient)
                    .principal(new UsernamePasswordAuthenticationToken(
                            user.getId().toString(), null, java.util.List.of()))
                    .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                    .authorizedScopes(registeredClient.getScopes())
                    .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                    .authorizationGrantType(DIRECT_GRANT)
                    // Ticket 012: lets AdminClaimsCustomizer stamp role/tenant_id
                    // claims without a second DB lookup. NOT a generic .put() —
                    // JwtGenerator's internal JwtEncodingContext.with(...) only
                    // copies specific recognized fields (confirmed by reading
                    // its real bytecode, not assumed), and authorizationGrant
                    // is one of them (copied whenever non-null). principal stays
                    // the plain user-id string so the `sub` claim is unaffected.
                    .authorizationGrant(
                            new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()))
                    .build();

            Object token = jwtGenerator.generate(context);
            if (!(token instanceof Jwt jwt)) {
                throw new IllegalStateException("JwtGenerator did not produce a JWT access token");
            }
            return jwt;
        } finally {
            AuthorizationServerContextHolder.resetContext();
        }
    }

    /**
     * {@code TenantAwareRegisteredClientRepository.findByClientId} returns
     * {@code null} (not {@code Optional}, matching Spring Security's own
     * {@code RegisteredClientRepository} contract) when the underlying
     * {@code IdentityClient} isn't found — SonarQube (S2259) correctly
     * flags the two call sites above as a real NPE risk if that null ever
     * reaches {@link #generateAccessToken}. In practice the caller has
     * already resolved the same {@code IdentityClient} moments earlier
     * (via {@code ClientContextResolver} or a stored {@code RefreshToken}),
     * so this should never actually be null — this is defense in depth
     * against that invariant ever silently breaking, not a normal,
     * user-facing error path.
     */
    private RegisteredClient requireRegisteredClient(String clientId) {
        RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
        if (registeredClient == null) {
            throw new IllegalStateException("No RegisteredClient found for clientId '" + clientId + "'");
        }
        return registeredClient;
    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
