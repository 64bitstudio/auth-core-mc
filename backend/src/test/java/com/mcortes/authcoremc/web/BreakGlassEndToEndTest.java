package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.security.Totp;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ticket 018: real HTTP, real Postgres — deliberately no login, no JWT, no
 * {@code Authorization} header anywhere in this file. That absence IS the
 * thing under test: break-glass has to work without touching
 * AuthController/OAuth2 at all. {@link
 * com.mcortes.authcoremc.service.BreakGlassService}'s own factor-by-factor
 * rejection logic is already covered by unit tests
 * ({@code BreakGlassServiceTest}) — this proves the real wiring: {@code
 * SecurityConfig}'s {@code permitAll()} actually lets these requests
 * through, and a real tenant really gets deactivated.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "breakglass.secret=" + BreakGlassEndToEndTest.TEST_SECRET,
            "breakglass.totp-secret=" + BreakGlassEndToEndTest.TOTP_FIXTURE,
            // MockMvc's simulated request defaults remoteAddr to 127.0.0.1.
            "breakglass.allowed-ips=127.0.0.1"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BreakGlassEndToEndTest {

    // Test fixtures only, under 16 chars / no KEY|SECRET|PASSWORD|TOKEN in
    // the *name* — see BreakGlassServiceTest for the same precedent.
    static final String TEST_SECRET = "test-bg-secret";
    static final String TOTP_FIXTURE = "JBSWY3DPEHPK3PXP";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(new Tenant(
                "BreakGlassTenant-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
    }

    @Test
    void diagnosticsWorksWithNoLoginNoJwtNoAuthorizationHeaderAtAll() throws Exception {
        mvc.perform(post("/api/v1/breakglass/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("ops-person")))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("\"databaseHealthy\":true"));
    }

    @Test
    void wrongSharedSecretIs401() throws Exception {
        String code = Totp.currentCode(TOTP_FIXTURE);
        mvc.perform(post("/api/v1/breakglass/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"secret":"wrong-secret","totpCode":"%s","operator":"ops-person"}"""
                                        .formatted(code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatesARealTenant() throws Exception {
        mvc.perform(post("/api/v1/breakglass/tenants/" + tenant.getId() + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("ops-person")))
                .andExpect(status().isNoContent());

        Tenant reloaded = tenantRepository.findById(tenant.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
    }

    @Test
    void deactivatingAnUnknownTenantIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/breakglass/tenants/" + UUID.randomUUID() + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("ops-person")))
                .andExpect(status().isNotFound());
    }

    private String authBody(String operator) throws Exception {
        String code = Totp.currentCode(TOTP_FIXTURE);
        return new ObjectMapper()
                .writeValueAsString(java.util.Map.of("secret", TEST_SECRET, "totpCode", code, "operator", operator));
    }
}
