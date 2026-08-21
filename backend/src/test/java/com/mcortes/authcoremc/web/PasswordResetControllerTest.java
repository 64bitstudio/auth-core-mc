package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.PasswordResetService;
import com.mcortes.authcoremc.service.WeakPasswordException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(PasswordResetController.class)
@Import(SecurityConfig.class)
class PasswordResetControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private PasswordResetService passwordResetService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void requestAlwaysReturns202EvenForAnUnknownIdentifier() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);

        mvc.post()
                .uri("/api/v1/password-reset/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"ghost@example.com\"}")
                .exchange()
                .assertThat()
                .hasStatus(202);

        verify(passwordResetService).requestReset(tenant, "ghost@example.com");
    }

    @Test
    void returns200OnASuccessfulConfirmation() {
        mvc.post()
                .uri("/api/v1/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"the-token\",\"newPassword\":\"newpass123\"}")
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(passwordResetService).confirmReset("the-token", "newpass123");
    }

    @Test
    void returns400ForAnInvalidOrExpiredToken() {
        doThrow(new InvalidTokenException("expired")).when(passwordResetService).confirmReset(any(), any());

        mvc.post()
                .uri("/api/v1/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"expired-token\",\"newPassword\":\"newpass123\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }

    @Test
    void returns400ForAWeakNewPassword() {
        doThrow(new WeakPasswordException("too weak")).when(passwordResetService).confirmReset(any(), any());

        mvc.post()
                .uri("/api/v1/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"the-token\",\"newPassword\":\"weak\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }
}
