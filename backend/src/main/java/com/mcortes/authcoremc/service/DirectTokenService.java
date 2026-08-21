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

    @Transactional
    public TokenPair issueTokens(IdentityClient client, User user) {
        if (!client.isFirstParty()) {
            throw new NotFirstPartyClientException();
        }

        RegisteredClient registeredClient = registeredClientRepository.findByClientId(client.getClientId());
        Jwt accessToken = generateAccessToken(registeredClient, user);

        String rawRefreshToken = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusSeconds(
                registeredClient.getTokenSettings().getRefreshTokenTimeToLive().getSeconds());
        refreshTokenRepository.save(
                new RefreshToken(user, client, TokenHasher.sha256(rawRefreshToken), expiresAt));

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

        RegisteredClient registeredClient =
                registeredClientRepository.findByClientId(stored.getClient().getClientId());
        Jwt accessToken = generateAccessToken(registeredClient, stored.getUser());
        long expiresInSeconds = registeredClient.getTokenSettings().getAccessTokenTimeToLive().getSeconds();

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

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
