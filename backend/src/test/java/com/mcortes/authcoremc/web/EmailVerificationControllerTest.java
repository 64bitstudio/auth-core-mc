package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.EmailVerificationService;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.TooManyAttemptsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(EmailVerificationController.class)
@Import(SecurityConfig.class)
class EmailVerificationControllerTest {

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

    @MockitoBean
    private TenantScopedUserResolver userResolver;

    @MockitoBean
    private EmailVerificationService verificationService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    private final UUID userId = UUID.randomUUID();

    @Test
    void returns202OnASuccessfulVerificationRequest() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(userResolver.resolve(tenant, userId)).thenReturn(user);

        mvc.post()
                .uri("/api/v1/verify-email/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")
                .exchange()
                .assertThat()
                .hasStatus(202);

        verify(verificationService).requestVerification(user);
    }

    @Test
    void returns429WhenTheResendCooldownIsActive() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(userResolver.resolve(tenant, userId)).thenReturn(user);
        doThrow(new TooManyAttemptsException("cooldown")).when(verificationService).requestVerification(user);

        mvc.post()
                .uri("/api/v1/verify-email/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")
                .exchange()
                .assertThat()
                .hasStatus(429);
    }

    @Test
    void returns200OnASuccessfulConfirmation() {
        mvc.post()
                .uri("/api/v1/verify-email/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"the-token\"}")
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(verificationService).confirmVerification("the-token");
    }

    @Test
    void returns400ForAnInvalidOrExpiredToken() {
        doThrow(new InvalidTokenException("expired")).when(verificationService).confirmVerification(any());

        mvc.post()
                .uri("/api/v1/verify-email/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"expired-token\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }
}
