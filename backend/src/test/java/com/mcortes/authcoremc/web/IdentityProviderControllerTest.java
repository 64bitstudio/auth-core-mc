package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.TenantIdentityProviderService;
import com.mcortes.authcoremc.service.UnsupportedProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * These endpoints are deliberately NOT in SecurityConfig's permitAll list —
 * see its Javadoc. @WithMockUser simulates "some authenticated caller" so
 * the business-logic tests aren't blocked on ticket 007's real tenant-admin
 * authentication; the no-annotation test proves the fail-closed default.
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

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void returns401ForAnUnauthenticatedCaller() {
        mvc.get().uri("/api/v1/identity-providers").header("X-Client-Id", "acme-web-app").exchange().assertThat().hasStatus(
                401);
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
