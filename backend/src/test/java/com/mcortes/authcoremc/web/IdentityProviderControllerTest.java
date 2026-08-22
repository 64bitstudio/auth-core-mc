package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.oauth2.SocialLoginFailureHandler;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.TenantIdentityProviderService;
import com.mcortes.authcoremc.service.UnsupportedProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * These endpoints are deliberately NOT in SecurityConfig's permitAll list —
 * see its Javadoc. @WithMockUser simulates "some authenticated caller" so
 * the business-logic tests aren't blocked on real Bearer-JWT authentication;
 * the no-annotation test proves the fail-closed default.
 *
 * <p>{@code jwtDecoder} is mocked, never stubbed to return anything (ticket
 * 012): SecurityConfig's {@code .oauth2ResourceServer(...)} DSL needs a
 * {@code JwtDecoder} bean to even build the filter chain, but this slice
 * test doesn't want the full {@code AuthorizationServerConfig} (a separate,
 * heavier filter chain of its own) — a real end-to-end Bearer-JWT proof
 * lives in {@code AdminRoleGateIntegrationTest} instead.
 */
@WebMvcTest(IdentityProviderController.class)
@Import(SecurityConfig.class)
class IdentityProviderControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private TenantIdentityProviderService providerService;

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

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void returns401ForAnUnauthenticatedCaller() {
        mvc.get().uri("/api/v1/identity-providers").header("X-Client-Id", "acme-web-app").exchange().assertThat().hasStatus(
                401);
    }

    /**
     * Regression test: SecurityConfig used to enable {@code httpBasic()},
     * whose 401s always carried a {@code WWW-Authenticate: Basic} header —
     * harmless for an API client, but it makes a real browser pop up its own
     * native username/password dialog on any request that isn't in
     * permitAll. This app has no HTTP Basic auth flow anywhere, so that
     * header should never be sent. See SecurityConfig's Javadoc.
     */
    @Test
    void aFailClosed401NeverCarriesAWwwAuthenticateChallengeHeader() {
        mvc.get()
                .uri("/api/v1/identity-providers")
                .header("X-Client-Id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(401)
                .doesNotContainHeader("WWW-Authenticate")
                .bodyText()
                .contains("unauthorized");
    }

    @Test
    @WithMockUser
    void listReturnsTheConfiguredProvidersWithoutTheSecret() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        TenantIdentityProvider google = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        google.configure("client-id", "encrypted-secret");
        when(providerService.list(tenant)).thenReturn(List.of(google));

        mvc.get()
                .uri("/api/v1/identity-providers")
                .header("X-Client-Id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("client-id")
                .doesNotContain("encrypted-secret");
    }

    @Test
    @WithMockUser
    void configuringGoogleReturns200WithoutTheSecretInTheResponse() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        TenantIdentityProvider google = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        google.configure("client-id", "encrypted-secret");
        when(providerService.configure(eq(tenant), eq(IdentityProviderType.GOOGLE), eq("client-id"), eq("raw-secret")))
                .thenReturn(google);

        mvc.put()
                .uri("/api/v1/identity-providers/GOOGLE")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"client-id\",\"clientSecret\":\"raw-secret\"}")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .doesNotContain("raw-secret")
                .doesNotContain("encrypted-secret");
    }

    @Test
    @WithMockUser
    void configuringAppleReturns400() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(providerService.configure(eq(tenant), eq(IdentityProviderType.APPLE), any(), any()))
                .thenThrow(new UnsupportedProviderException("Apple Developer Program membership required"));

        mvc.put()
                .uri("/api/v1/identity-providers/APPLE")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"client-id\",\"clientSecret\":\"raw-secret\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }

    @Test
    @WithMockUser
    void disablingReturns204() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.delete()
                .uri("/api/v1/identity-providers/GOOGLE")
                .header("X-Client-Id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(204);

        verify(providerService).disable(tenant, IdentityProviderType.GOOGLE);
    }
}
