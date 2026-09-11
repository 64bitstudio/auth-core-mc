package com.mcortes.authcoremc.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ticket 054, end to end: proves the real {@code SecurityConfig} wiring
 * (not just {@link CorsConfig} in isolation) against a real preflight —
 * the exact request shape that exposed the original gap (no {@code
 * Access-Control-Allow-Origin} at all, verified live against DEV before
 * this ticket, see PROP-GS-AUTH-01).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=https://studio-dev.galgoth.64bitstudio.com")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsConfigurationIntegrationTest {

    private static final String ALLOWED_ORIGIN = "https://studio-dev.galgoth.64bitstudio.com";

    @Autowired
    private MockMvc mvc;

    @Test
    void aRealPreflightFromTheAllowedOriginGetsTheHeader() throws Exception {
        mvc.perform(options("/api/v1/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-client-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    /**
     * Not "200 without the header" — Spring Security's CorsFilter rejects
     * outright any request (preflight or not) that carries an Origin header
     * not in the allowlist, once a CorsConfigurationSource is registered for
     * the path at all. A request with no Origin header (any non-browser
     * caller — curl, Postman, another backend) is untouched by this and
     * never even reaches this check.
     */
    @Test
    void aPreflightFromAnUnlistedOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/login")
                        .header(HttpHeaders.ORIGIN, "https://attacker.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name()))
                .andExpect(status().isForbidden());
    }
}
