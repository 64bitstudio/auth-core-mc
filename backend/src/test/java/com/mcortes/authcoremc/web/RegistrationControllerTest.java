package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.DuplicateIdentifierException;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.RegistrationService;
import com.mcortes.authcoremc.service.WeakPasswordException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// @WebMvcTest doesn't pick up a plain @Configuration like SecurityConfig on
// its own (it only auto-scans controller-layer beans) — without importing
// it, Spring falls back to Security's own defaults (CSRF on, deny-all),
// which is why every POST here would otherwise 403 regardless of the
// mocked service behavior.
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    @Autowired
    private MockMvcTester mvc;

    // Ticket 012: SecurityConfig's .oauth2ResourceServer(...) needs a JwtDecoder
    // bean to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private RegistrationService registrationService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void returns201AndTheUserWithoutThePasswordHashOnSuccess() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "argon2-hash");
        when(registrationService.register(eq(tenant), eq("ada@example.com"), any(), eq("Ada"), eq("Lovelace"), eq("abcd1234")))
                .thenReturn(user);

        mvc.post()
                .uri("/api/v1/register")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"email":"ada@example.com","nombre":"Ada","apellidos":"Lovelace","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(201)
                .bodyText()
                .contains("ada@example.com")
                .doesNotContain("argon2-hash");
    }

    @Test
    void returns401WhenTheClientIdIsUnknown() {
        when(clientContextResolver.resolveTenant("ghost")).thenThrow(new UnknownClientException("ghost"));

        mvc.post()
                .uri("/api/v1/register")
                .header("X-Client-Id", "ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"email":"ada@example.com","nombre":"Ada","apellidos":"Lovelace","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(401);
    }

    @Test
    void returns400ForAWeakPassword() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(registrationService.register(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WeakPasswordException("too weak"));

        mvc.post()
                .uri("/api/v1/register")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"email":"ada@example.com","nombre":"Ada","apellidos":"Lovelace","password":"weak"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400);
    }

    @Test
    void returns409ForADuplicateIdentifier() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(registrationService.register(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateIdentifierException("already registered"));

        mvc.post()
                .uri("/api/v1/register")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"email":"ada@example.com","nombre":"Ada","apellidos":"Lovelace","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(409);
    }

    @Test
    void returns400WhenNombreIsMissing() {
        mvc.post()
                .uri("/api/v1/register")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"email":"ada@example.com","apellidos":"Lovelace","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400);
    }
}
