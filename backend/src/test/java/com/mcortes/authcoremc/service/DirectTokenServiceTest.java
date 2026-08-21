package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.oauth2.AdminClaimsCustomizer;
import com.mcortes.authcoremc.oauth2.TenantAwareRegisteredClientRepository;
import com.nimbusds.jwt.SignedJWT;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.RefreshTokenRepository;
import com.mcortes.authcoremc.security.TokenHasher;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.test.util.ReflectionTestUtils;

class DirectTokenServiceTest {

    private final IdentityClientRepository identityClientRepository = mock(IdentityClientRepository.class);
    private final TenantAwareRegisteredClientRepository registeredClientRepository =
            new TenantAwareRegisteredClientRepository(identityClientRepository);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);

    private JwtGenerator jwtGenerator;
    private AuthorizationServerSettings settings;

    private Tenant tenant;
    private IdentityClient firstPartyClient;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        JwtEncoder encoder = new NimbusJwtEncoder(buildJwkSource());
        jwtGenerator = new JwtGenerator(encoder);
        jwtGenerator.setJwtCustomizer(new AdminClaimsCustomizer());
        settings = AuthorizationServerSettings.builder().issuer("https://auth.example.com").build();

        tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());

        firstPartyClient = new IdentityClient(
                tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback"));
        ReflectionTestUtils.setField(firstPartyClient, "id", UUID.randomUUID());

        user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    private DirectTokenService service() {
        return new DirectTokenService(registeredClientRepository, jwtGenerator, settings, refreshTokenRepository);
    }

    private static JWKSource<SecurityContext> buildJwkSource() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Test
    void issuingTokensForAFirstPartyClientReturnsASignedJwtAndAnOpaqueRefreshToken() {
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(firstPartyClient));

        TokenPair tokens = service().issueTokens(firstPartyClient, user);

        assertThat(tokens.accessToken().split("\\.")).hasSize(3); // header.payload.signature
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresInSeconds()).isEqualTo(900);
        assertThat(tokens.refreshToken()).isNotBlank();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(TokenHasher.sha256(tokens.refreshToken()));
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void issuedAccessTokenCarriesTheUsersRoleAndTenantIdClaims() throws Exception {
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(firstPartyClient));
        user.grantRole(UserRole.TENANT_ADMIN);

        TokenPair tokens = service().issueTokens(firstPartyClient, user);

        var claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("role")).isEqualTo("TENANT_ADMIN");
        assertThat(claims.getStringClaim("tenant_id")).isEqualTo(tenant.getId().toString());
    }

    @Test
    void aRegularUserWithNoAdminRoleStillGetsATokenWithTheNoneRoleClaim() throws Exception {
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(firstPartyClient));

        TokenPair tokens = service().issueTokens(firstPartyClient, user);

        var claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
        assertThat(claims.getStringClaim("role")).isEqualTo("NONE");
    }

    @Test
    void issuingTokensForANonFirstPartyClientIsRejected() {
        IdentityClient thirdParty = new IdentityClient(
                tenant, "partner-app", "hashed-secret", false, List.of("https://partner.example.com/callback"));

        assertThatThrownBy(() -> service().issueTokens(thirdParty, user)).isInstanceOf(NotFirstPartyClientException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refreshingAValidTokenReturnsANewAccessToken() {
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(firstPartyClient));
        RefreshToken stored = new RefreshToken(user, firstPartyClient, TokenHasher.sha256("raw-refresh-token"),
                Instant.now().plus(30, ChronoUnit.DAYS));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256("raw-refresh-token")))
                .thenReturn(Optional.of(stored));

        TokenPair tokens = service().refresh("raw-refresh-token");

        assertThat(tokens.accessToken().split("\\.")).hasSize(3);
        assertThat(tokens.refreshToken()).isEqualTo("raw-refresh-token");
    }

    @Test
    void refreshingARevokedTokenIsRejected() {
        RefreshToken stored = new RefreshToken(user, firstPartyClient, TokenHasher.sha256("raw-refresh-token"),
                Instant.now().plus(30, ChronoUnit.DAYS));
        stored.revoke();
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256("raw-refresh-token")))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service().refresh("raw-refresh-token")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refreshingAnExpiredTokenIsRejected() {
        RefreshToken stored = new RefreshToken(user, firstPartyClient, TokenHasher.sha256("raw-refresh-token"),
                Instant.now().minus(1, ChronoUnit.DAYS));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256("raw-refresh-token")))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service().refresh("raw-refresh-token")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refreshingAnUnknownTokenIsRejected() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().refresh("garbage")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revokingMarksTheStoredTokenAsRevoked() {
        RefreshToken stored = new RefreshToken(user, firstPartyClient, TokenHasher.sha256("raw-refresh-token"),
                Instant.now().plus(30, ChronoUnit.DAYS));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256("raw-refresh-token")))
                .thenReturn(Optional.of(stored));

        service().revoke("raw-refresh-token");

        assertThat(stored.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void revokingAnUnknownTokenDoesNothing() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        service().revoke("garbage");

        verify(refreshTokenRepository, never()).save(any());
    }
}
