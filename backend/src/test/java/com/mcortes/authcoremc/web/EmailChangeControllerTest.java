package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.DuplicateIdentifierException;
import com.mcortes.authcoremc.service.EmailChangeService;
import com.mcortes.authcoremc.service.InvalidTokenException;
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

@WebMvcTest(EmailChangeController.class)
@Import(SecurityConfig.class)
class EmailChangeControllerTest {

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
    private EmailChangeService emailChangeService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    private final UUID userId = UUID.randomUUID();

    @Test
    void returns202OnASuccessfulChangeRequest() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(userResolver.resolve(tenant, userId)).thenReturn(user);

        mvc.post()
                .uri("/api/v1/change-email/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"newEmail\":\"ada.new@example.com\"}")
                .exchange()
                .assertThat()
                .hasStatus(202);

        verify(emailChangeService).requestChange(user, "ada.new@example.com");
    }

    @Test
    void returns409WhenTheNewEmailIsAlreadyTaken() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(userResolver.resolve(tenant, userId)).thenReturn(user);
        doThrow(new DuplicateIdentifierException("taken"))
                .when(emailChangeService)
                .requestChange(user, "taken@example.com");

        mvc.post()
                .uri("/api/v1/change-email/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"newEmail\":\"taken@example.com\"}")
                .exchange()
                .assertThat()
                .hasStatus(409);
    }

    @Test
    void returns200OnASuccessfulConfirmation() {
        mvc.post()
                .uri("/api/v1/change-email/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"the-token\"}")
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(emailChangeService).confirmChange("the-token");
    }

    @Test
    void returns400ForAnInvalidOrExpiredToken() {
        doThrow(new InvalidTokenException("expired")).when(emailChangeService).confirmChange(any());

        mvc.post()
                .uri("/api/v1/change-email/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"expired-token\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }
}
