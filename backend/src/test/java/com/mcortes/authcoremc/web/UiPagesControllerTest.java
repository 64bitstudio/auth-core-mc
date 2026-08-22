package com.mcortes.authcoremc.web;

import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.oauth2.SocialLoginFailureHandler;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.TenantIdentityProviderService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// See RegistrationControllerTest for why SecurityConfig must be imported explicitly here.
@WebMvcTest(UiPagesController.class)
@Import(SecurityConfig.class)
class UiPagesControllerTest {

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

    // Ticket 039: register()/login() now also resolve which social buttons
    // to show via this service — never stubbed to throw, only its list(...)
    // return value varies per test.
    @MockitoBean
    private TenantIdentityProviderService tenantIdentityProviderService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    // Ticket 039: same IdentityClient shape as
    // rendersTheAdminIdentityProvidersPageWithTheAdminShellNotTenantTheming
    // below — register()/login() now resolve the IdentityClient (not just
    // the Tenant) to build each provider's /oauth2/authorization/** link.
    private static final UUID IDENTITY_CLIENT_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    private IdentityClient identityClient(Tenant tenant) {
        IdentityClient client = new IdentityClient(tenant, "acme-web-app", "secret-hash", true, List.of());
        ReflectionTestUtils.setField(client, "id", IDENTITY_CLIENT_ID);
        return client;
    }

    @Test
    void rendersTheRegisterPageThemedForTheResolvedTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(identityClient(tenant));
        when(tenantIdentityProviderService.list(tenant)).thenReturn(List.of());

        mvc.get()
                .uri("/ui/register")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Acme App")
                .contains("#0057FF")
                // Ticket 039: neither provider is enabled for this tenant in
                // this test, so neither social button should render.
                .doesNotContain("Iniciar sesión con Google")
                .doesNotContain("Iniciar sesión con Facebook");
    }

    @Test
    void registerPageWithoutClientIdIsABadRequest() {
        mvc.get().uri("/ui/register").exchange().assertThat().hasStatus(400);
    }

    @Test
    void rendersTheLoginPageThemedForTheResolvedTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(identityClient(tenant));
        when(tenantIdentityProviderService.list(tenant)).thenReturn(List.of());

        mvc.get()
                .uri("/ui/login")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Acme App")
                .doesNotContain("Iniciar sesión con Google")
                .doesNotContain("Iniciar sesión con Facebook");
    }

    @Test
    void showsTheGoogleButtonOnLoginOnlyWhenGoogleIsEnabledForTheTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        IdentityClient client = identityClient(tenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(client);
        TenantIdentityProvider google = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        google.configure("google-client-id", "encrypted-secret");
        when(tenantIdentityProviderService.list(tenant)).thenReturn(List.of(google));

        mvc.get()
                .uri("/ui/login")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Iniciar sesión con Google")
                // Ticket 039: link built from SocialRegistrationId.of(...), the
                // same "{identityClientId}::{provider}" formatter ticket 044
                // already uses for admin-identity-providers.html.
                .contains("/oauth2/authorization/" + client.getId() + "::google")
                .doesNotContain("Iniciar sesión con Facebook");
    }

    @Test
    void showsTheFacebookButtonOnRegisterOnlyWhenFacebookIsEnabledForTheTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        IdentityClient client = identityClient(tenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(client);
        TenantIdentityProvider facebook = new TenantIdentityProvider(tenant, IdentityProviderType.FACEBOOK);
        facebook.configure("fb-client-id", "encrypted-secret");
        when(tenantIdentityProviderService.list(tenant)).thenReturn(List.of(facebook));

        mvc.get()
                .uri("/ui/register")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Iniciar sesión con Facebook")
                .contains("/oauth2/authorization/" + client.getId() + "::facebook")
                .doesNotContain("Iniciar sesión con Google");
    }

    @Test
    void aDisabledProviderNeverShowsItsButtonEvenIfPreviouslyConfigured() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(identityClient(tenant));
        TenantIdentityProvider google = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        google.configure("google-client-id", "encrypted-secret");
        google.disable();
        when(tenantIdentityProviderService.list(tenant)).thenReturn(List.of(google));

        mvc.get()
                .uri("/ui/login")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .doesNotContain("Iniciar sesión con Google");
    }

    @Test
    void socialCallbackPageWithoutClientIdIsABadRequest() {
        mvc.get().uri("/ui/social-callback").exchange().assertThat().hasStatus(400);
    }

    @Test
    void rendersTheSocialLoginErrorPageWithoutNeedingAClientIdAndWithoutTenantTheming() {
        mvc.get()
                .uri("/ui/social-login-error")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("No se pudo iniciar sesión")
                .doesNotContain("Acme App");
    }

    // Ticket 039: consolidated per SonarQube java:S5976 ("Replace these 3
    // tests with a single Parameterized one") — social-callback joined the
    // two pre-existing individual tests below (cuenta, password-reset
    // request) that shared the exact same shape (theme a page from a
    // resolved tenant, assert 200 + the tenant's own appName renders).
    @ParameterizedTest
    @ValueSource(strings = {"/ui/social-callback", "/ui/cuenta", "/ui/password-reset/request"})
    void rendersThemedPagesForTheResolvedTenant(String path) {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri(path)
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Acme App");
    }

    @Test
    void anUnknownClientIdOnAThemedPageIsRejected() {
        when(clientContextResolver.resolveTenant("no-such-client")).thenThrow(new UnknownClientException("no-such-client"));

        mvc.get()
                .uri("/ui/register")
                .param("client_id", "no-such-client")
                .exchange()
                .assertThat()
                .hasStatus(401);
    }

    @Test
    void rendersTheAdminHomePageWithTheAdminShellNotTenantTheming() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/admin")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Auth Core MC — Admin")
                .contains("Bienvenido al panel de administración")
                .doesNotContain("Acme App");
    }

    @Test
    void rendersTheAdminIdentityProvidersPageWithTheAdminShellNotTenantTheming() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        UUID identityClientId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        IdentityClient identityClient = new IdentityClient(tenant, "acme-web-app", "secret-hash", true, List.of());
        ReflectionTestUtils.setField(identityClient, "id", identityClientId);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(identityClient);

        mvc.get()
                .uri("/ui/admin/identity-providers")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Auth Core MC — Admin")
                .contains("Proveedores de login social")
                // Ticket 020: the admin panel has its own fixed identity now —
                // it must NOT render the tenant's own branding anymore.
                .doesNotContain("Acme App")
                // Ticket 044: one CONCRETE, resolved value per provider — the
                // literal "{registrationId}" placeholder ticket 040 originally
                // showed here would never match Google/Facebook's real
                // redirect_uri validation (exact string match, no templates).
                .contains("http://localhost:8080/login/oauth2/code/" + identityClientId + "::google")
                .contains("http://localhost:8080/login/oauth2/code/" + identityClientId + "::facebook")
                .doesNotContain("{registrationId}");
    }

    @Test
    void rendersTheAdminMetricsPageWithTheAdminShellNotTenantTheming() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/admin/metrics")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Auth Core MC — Admin")
                .contains("Métricas de uso")
                .doesNotContain("Acme App");
    }

    @Test
    void rendersTheAdminTenantsPageWithTheAdminShellNotTenantTheming() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/admin/tenants")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Auth Core MC — Admin")
                .contains("Todos los clientes")
                .doesNotContain("Acme App");
    }

    @Test
    void confirmationPagesRenderWithoutNeedingAClientId() {
        mvc.get().uri("/ui/verify-email/confirm").exchange().assertThat().hasStatus(200);
        mvc.get().uri("/ui/change-email/confirm").exchange().assertThat().hasStatus(200);
        mvc.get().uri("/ui/password-reset/confirm").exchange().assertThat().hasStatus(200);
    }
}
