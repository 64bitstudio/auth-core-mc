package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Ticket 016: real HTTP, real JWT, real {@code login_event} rows produced
 * by real logins — same level as {@code AdminTenantEndToEndTest} (013) and
 * {@code AdminIdentityProviderEndToEndTest} (014). {@link
 * com.mcortes.authcoremc.service.AdminMetricsService}'s aggregation math is
 * already covered by unit tests ({@code AdminMetricsServiceTest}) — this
 * proves the wiring: real login_event rows → real HTTP query → correct
 * numbers, plus the role/tenant gate against a real request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminMetricsEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    private String clientId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        clientId = "acme-metrics-e2e-" + UUID.randomUUID();
        tenant = tenantRepository.save(
                new Tenant("MetricsTenant-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        identityClientRepository.save(
                new IdentityClient(tenant, clientId, null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void aPlatformAdminSeesTheRealCountsFromRealLoginEvents() throws Exception {
        String adminToken = loginAs("platform-admin@example.com", UserRole.PLATFORM_ADMIN);
        // loginAs already produced one SUCCESS login_event for platform-admin@example.com.
        // One more failed attempt, same account, to have both outcomes represented.
        mvc.perform(post("/api/v1/login")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"identifier":"platform-admin@example.com","password":"wrong-password"}"""));

        mvc.perform(get("/api/v1/admin/tenants/" + tenant.getId() + "/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("\"totalLogins\":2")
                        .contains("\"successCount\":1")
                        .contains("\"failureCount\":1")
                        .contains("\"PASSWORD\":2"));
    }

    @Test
    void aTenantAdminCanQueryItsOwnTenantButNotAnotherOne() throws Exception {
        String token = loginAs("tenant-admin@example.com", UserRole.TENANT_ADMIN);
        Tenant otherTenant = tenantRepository.save(new Tenant(
                "MetricsOther-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));

        mvc.perform(get("/api/v1/admin/tenants/" + tenant.getId() + "/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/tenants/" + otherTenant.getId() + "/metrics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTenantWithNoActivityReturnsAllZeroMetricsNotAnError() throws Exception {
        Tenant emptyTenant = tenantRepository.save(new Tenant(
                "MetricsEmpty-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        String adminToken = loginAs("platform-admin2@example.com", UserRole.PLATFORM_ADMIN);

        mvc.perform(get("/api/v1/admin/tenants/" + emptyTenant.getId() + "/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content ->
                        assertThat(content.getResponse().getContentAsString()).contains("\"totalLogins\":0"));
    }

    @Test
    void aFromAfterToIsRejectedWithABadRequest() throws Exception {
        String adminToken = loginAs("platform-admin3@example.com", UserRole.PLATFORM_ADMIN);

        mvc.perform(get("/api/v1/admin/tenants/" + tenant.getId() + "/metrics")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("from", "2026-01-10T00:00:00Z")
                        .param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    private String loginAs(String email, UserRole role) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mvc.perform(post("/api/v1/register")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        Map.of("email", email, "nombre", "Test", "apellidos", "User", "password", "abcd1234"))));

        var user = userRepository.findByTenantAndEmail(tenant, email).orElseThrow();
        user.grantRole(role);
        userRepository.save(user);

        String responseBody = mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", email, "password", "abcd1234"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return (String) ((Map<?, ?>) mapper.readValue(responseBody, Map.class).get("tokens")).get("accessToken");
    }
}
