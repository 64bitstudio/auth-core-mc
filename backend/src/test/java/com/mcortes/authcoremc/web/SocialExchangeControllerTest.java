package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SocialLoginFailureHandler;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.TokenPair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// See RegistrationControllerTest for why SecurityConfig must be imported explicitly here.
@WebMvcTest(SocialExchangeController.class)
@Import(SecurityConfig.class)
class SocialExchangeControllerTest {

    @Autowired
    private MockMvcTester mvc;

    // Ticket 012: SecurityConfig's .oauth2ResourceServer(...) needs a JwtDecoder
    // bean to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // Ticket 036: SecurityConfig's .oauth2Login(...) needs a ClientRegistrationRepository
    // bean to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // Ticket 037: SecurityConfig's .oauth2Login(...) needs the Social*Handler
    // beans to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private SocialLoginSuccessHandler socialLoginSuccessHandler;

    @MockitoBean
    private SocialLoginFailureHandler socialLoginFailureHandler;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private RedisTokenStore redisTokenStore;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private DirectTokenService directTokenService;

    private final Tenant tenant = tenantFixture();
    private final Tenant otherTenant = tenantFixture();
    private final IdentityClient firstPartyClient = clientFixture(tenant, "acme-web-app", true);
    private final IdentityClient thirdPartyClient = clientFixture(tenant, "partner-app", false);

    private static Tenant tenantFixture() {
        Tenant t = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    private static IdentityClient clientFixture(Tenant tenant, String clientId, boolean firstParty) {
        IdentityClient client = new IdentityClient(tenant, clientId, null, firstParty, List.of());
        ReflectionTestUtils.setField(client, "id", UUID.randomUUID());
        return client;
    }

    private static User userFixture(Tenant tenant) {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void returns200WithTheSameResponseShapeAsLoginOnASuccessfulExchange() {
        User user = userFixture(tenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(SocialLoginSuccessHandler.EXCHANGE_PURPOSE, "one-time-code"))
                .thenReturn(Optional.of(user.getId().toString()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(directTokenService.issueTokens(firstPartyClient, user))
                .thenReturn(new TokenPair("jwt-access-token", "opaque-refresh-token", "Bearer", 900));

        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"one-time-code"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                // Same UserResponse/TokenPair records /api/v1/login serializes —
                // this is the "{ user, tokens }" contract check.
                .contains("\"user\":")
                .contains("ada@example.com")
                .contains("\"hasPassword\":false")
                .contains("\"tokens\":")
                .contains("jwt-access-token")
                .contains("opaque-refresh-token")
                .contains("\"tokenType\":\"Bearer\"")
                .contains("\"expiresInSeconds\":900")
                .doesNotContain("password_hash");
    }

    @Test
    void returns400ForAnAlreadyUsedOrUnknownCode() {
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(SocialLoginSuccessHandler.EXCHANGE_PURPOSE, "already-used"))
                .thenReturn(Optional.empty());

        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"already-used"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        verify(userRepository, never()).findById(any());
        verify(directTokenService, never()).issueTokens(any(), any());
    }

    @Test
    void returns400ForAnExpiredCode() {
        // RedisTokenStore.consume() can't distinguish "expired" from "never
        // existed"/"already used" — an expired Redis key is simply absent,
        // same path as the "already used" test above. Kept as its own test
        // for the ticket's own explicit acceptance criterion.
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(SocialLoginSuccessHandler.EXCHANGE_PURPOSE, "expired-code"))
                .thenReturn(Optional.empty());

        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"expired-code"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");
    }

    @Test
    void returns400ForANonexistentCode() {
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(SocialLoginSuccessHandler.EXCHANGE_PURPOSE, "never-issued"))
                .thenReturn(Optional.empty());

        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"never-issued"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");
    }

    @Test
    void returns403ForAThirdPartyClientWithoutEvenConsumingTheCode() {
        when(clientContextResolver.resolveClient("partner-app")).thenReturn(thirdPartyClient);

        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "partner-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"some-code"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(403);

        verify(redisTokenStore, never()).consume(any(), any());
    }

    @Test
    void returns400WhenTheResolvedUserBelongsToADifferentTenantThanTheClient() {
        User user = userFixture(otherTenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(SocialLoginSuccessHandler.EXCHANGE_PURPOSE, "cross-tenant-code"))
                .thenReturn(Optional.of(user.getId().toString()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"cross-tenant-code"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        verify(directTokenService, never()).issueTokens(any(), any());
    }

    /** Same regression shape as AuthControllerTest's — see SecurityConfig's Javadoc. */
    @Test
    void aMissingClientIdHeaderIsAPlain400NotA401() {
        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"some-code"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .doesNotContainHeader("WWW-Authenticate");
    }

    @Test
    void returns400WhenTheCodeFieldIsMissing() {
        mvc.post()
                .uri("/api/v1/oauth2/social-exchange")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("validation_error");
    }
}
