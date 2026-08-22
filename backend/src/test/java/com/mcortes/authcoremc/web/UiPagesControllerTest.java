package com.mcortes.authcoremc.web;

import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void rendersTheRegisterPageThemedForTheResolvedTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/register")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Acme App")
                .contains("#0057FF");
    }

    @Test
    void registerPageWithoutClientIdIsABadRequest() {
        mvc.get().uri("/ui/register").exchange().assertThat().hasStatus(400);
    }

    @Test
    void rendersTheLoginPageThemedForTheResolvedTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/login")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Acme App");
    }

    @Test
    void rendersTheCuentaPageThemedForTheResolvedTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/cuenta")
                .param("client_id", "acme-web-app")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("Acme App");
    }

    @Test
    void rendersThePasswordResetRequestPageThemedForTheResolvedTenant() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.get()
                .uri("/ui/password-reset/request")
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
                // Ticket 040: the redirect_uri block — built from the
                // app.base-url test default (see application.properties) plus
                // Spring's own oauth2Login() callback path convention, and
                // shown once per provider card since both cards render it.
                .contains("http://localhost:8080/login/oauth2/code/{registrationId}");
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
