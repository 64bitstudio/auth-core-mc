package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.TokenPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(TokenController.class)
@Import(SecurityConfig.class)
class TokenControllerTest {

    @Autowired
    private MockMvcTester mvc;

    // Ticket 012: SecurityConfig's .oauth2ResourceServer(...) needs a JwtDecoder
    // bean to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private DirectTokenService directTokenService;

    @Test
    void refreshReturns200WithNewTokens() {
        when(directTokenService.refresh("raw-refresh-token"))
                .thenReturn(new TokenPair("new-access-token", "raw-refresh-token", "Bearer", 900));

        mvc.post()
                .uri("/api/v1/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"raw-refresh-token\"}")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("new-access-token");
    }

    @Test
    void refreshReturns400ForAnInvalidToken() {
        when(directTokenService.refresh(any())).thenThrow(new InvalidTokenException("invalid"));

        mvc.post()
                .uri("/api/v1/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"garbage\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }

    @Test
    void revokeReturns204() {
        mvc.post()
                .uri("/api/v1/token/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"raw-refresh-token\"}")
                .exchange()
                .assertThat()
                .hasStatus(204);

        verify(directTokenService).revoke("raw-refresh-token");
    }

    @Test
    void refreshReturns400WhenTheTokenFieldIsMissing() {
        mvc.post()
                .uri("/api/v1/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }
}
