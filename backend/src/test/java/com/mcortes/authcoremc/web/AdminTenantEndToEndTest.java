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
 * Ticket 013, end to end: this is the real admin endpoint ticket 012 was
 * missing to prove its role gate against (see that ticket's Hecho — the
 * {@code /api/v1/admin/**} rule was only proven generically until now).
 * Real login, real JWT, real HTTP requests — no mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminTenantEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    private String clientId;
    private Tenant loginTenant;

    @BeforeEach
    void setUp() {
        clientId = "acme-admin-e2e-" + UUID.randomUUID();
        loginTenant = tenantRepository.save(
                new Tenant("LoginTenant-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        identityClientRepository.save(
                new IdentityClient(loginTenant, clientId, null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void aPlatformAdminCanListAllTenants() throws Exception {
        String adminToken = loginAs("platform-admin-list@example.com", UserRole.PLATFORM_ADMIN);
        Tenant otherTenant = tenantRepository.save(new Tenant(
                "ListOther-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));

        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains(loginTenant.getId().toString())
                        .contains(otherTenant.getId().toString()));
    }

    @Test
    void aTenantAdminCannotListAllTenants() throws Exception {
        String token = loginAs("tenant-admin-list@example.com", UserRole.TENANT_ADMIN);

        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlatformAdminCanCreateATenantThroughTheRealEndpoint() throws Exception {
        String token = loginAs("platform-admin@example.com", UserRole.PLATFORM_ADMIN);

        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson("NewTenant-" + UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    void aUserWithNoAdminRoleIsRejectedWith403() throws Exception {
        String token = loginAs("nobody@example.com", UserRole.NONE);

        mvc.perform(post("/api/v1/admin/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantJson("ShouldNotExist-" + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTenantAdminCanReadItsOwnTenantButNotAnotherOne() throws Exception {
        Tenant otherTenant = tenantRepository.save(
                new Tenant("OtherTenant-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        String token = loginAs("tenant-admin@example.com", UserRole.TENANT_ADMIN);

        mvc.perform(get("/api/v1/admin/tenants/" + loginTenant.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/tenants/" + otherTenant.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private String loginAs(String email, UserRole role) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mvc.perform(post("/api/v1/register")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        Map.of("email", email, "nombre", "Test", "apellidos", "User", "password", "abcd1234"))));

        var user = userRepository.findByTenantAndEmail(loginTenant, email).orElseThrow();
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

    private String createTenantJson(String name) {
        return """
                {"name":"%s","appName":"App","primaryColor":"#0057FF","accessTokenTtlSeconds":900,\
                "refreshTokenTtlSeconds":2592000,"emailVerificationTtlSeconds":86400,\
                "passwordResetTtlSeconds":3600,"otpTtlSeconds":300}"""
                .formatted(name);
    }
}
