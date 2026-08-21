package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.AuthenticationService;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.InvalidCredentialsException;
import com.mcortes.authcoremc.service.TokenPair;
import com.mcortes.authcoremc.service.TooManyAttemptsException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// See RegistrationControllerTest for why SecurityConfig must be imported explicitly here.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private DirectTokenService directTokenService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    private final IdentityClient firstPartyClient =
            new IdentityClient(tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback"));
    private final IdentityClient thirdPartyClient = new IdentityClient(
            tenant, "partner-app", "hashed-secret", false, List.of("https://partner.example.com/callback"));

    @Test
    void returns200WithUserAndTokensOnSuccessfulLogin() {
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "argon2-hash");
        when(authenticationService.authenticate(eq(tenant), eq("ada@example.com"), eq("abcd1234")))
                .thenReturn(user);
        when(directTokenService.issueTokens(firstPartyClient, user))
                .thenReturn(new TokenPair("jwt-access-token", "opaque-refresh-token", "Bearer", 900));

        mvc.post()
                .uri("/api/v1/login")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":"ada@example.com","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("ada@example.com")
                .contains("jwt-access-token")
                .contains("opaque-refresh-token")
                .doesNotContain("argon2-hash");
    }

    @Test
    void returns403ForAThirdPartyClientWithoutEvenCheckingCredentials() {
        when(clientContextResolver.resolveClient("partner-app")).thenReturn(thirdPartyClient);

        mvc.post()
                .uri("/api/v1/login")
                .header("X-Client-Id", "partner-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":"ada@example.com","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(403);

        verify(authenticationService, never()).authenticate(any(), any(), any());
    }

    @Test
    void returns401ForInvalidCredentials() {
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(authenticationService.authenticate(any(), any(), any())).thenThrow(new InvalidCredentialsException());

        mvc.post()
                .uri("/api/v1/login")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":"ada@example.com","password":"wrong"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(401);
    }

    @Test
    void returns429WhenRateLimited() {
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(authenticationService.authenticate(any(), any(), any()))
                .thenThrow(new TooManyAttemptsException("blocked"));

        mvc.post()
                .uri("/api/v1/login")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identifier":"ada@example.com","password":"abcd1234"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(429);
    }
}
