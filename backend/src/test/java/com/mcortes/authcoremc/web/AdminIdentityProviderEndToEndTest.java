package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Ticket 014: real HTTP, real JWT, no mocks — same level as
 * {@code AdminTenantEndToEndTest} (ticket 013). {@link
 * com.mcortes.authcoremc.service.TenantIdentityProviderService}'s own
 * business logic (configure/disable/list, encryption, Apple rejection) is
 * already covered by {@code TenantIdentityProviderServiceTest} (tickets
 * 006/017) — this only proves the new admin wiring: JWT → own tenant →
 * real delegation to that untouched service.
 *
 * <p>{@code configure()} goes through the real {@code TenantSecretEncryptor}
 * / {@code VaultTransitEncryptor} beans (envelope encryption, ticket 017),
 * so this test needs a real Vault too — same hermetic
 * "own container per test run" pattern as {@code TenantSecretEncryptorTest},
 * just wired via Spring properties instead of built by hand, since here
 * the beans themselves are Spring-managed.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminIdentityProviderEndToEndTest {

    private static final String VAULT_ROOT_TOKEN = "test-root-token";

    @Container
    private static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:1.19")
            .withVaultToken(VAULT_ROOT_TOKEN)
            .withInitCommand("secrets enable transit", "write -f transit/keys/auth-core-mc-tenant-keys");

    @DynamicPropertySource
    static void vaultProperties(DynamicPropertyRegistry registry) {
        registry.add("vault.address", () -> "http://" + VAULT.getHost() + ":" + VAULT.getFirstMappedPort());
        registry.add("vault.token", () -> VAULT_ROOT_TOKEN);
    }

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
        clientId = "acme-idp-admin-e2e-" + UUID.randomUUID();
        tenant = tenantRepository.save(
                new Tenant("IdpAdminTenant-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        identityClientRepository.save(
                new IdentityClient(tenant, clientId, null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void aTenantAdminCanConfigureListAndDisableAProviderForItsOwnTenant() throws Exception {
        String token = loginAs("tenant-admin@example.com", UserRole.TENANT_ADMIN);

        mvc.perform(put("/api/v1/admin/identity-providers/GOOGLE")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"google-client-id","clientSecret":"google-client-secret"}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/identity-providers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("\"GOOGLE\"")
                        .contains("\"enabled\":true")
                        .contains("google-client-id")
                        .doesNotContain("google-client-secret"));

        mvc.perform(delete("/api/v1/admin/identity-providers/GOOGLE").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/admin/identity-providers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content ->
                        assertThat(content.getResponse().getContentAsString()).contains("\"enabled\":false"));
    }

    @Test
    void aUserWithNoAdminRoleIsRejectedWith403() throws Exception {
        String token = loginAs("nobody@example.com", UserRole.NONE);

        mvc.perform(get("/api/v1/admin/identity-providers").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void appleIsRejectedAsUnsupportedThroughTheAdminEndpointToo() throws Exception {
        String token = loginAs("platform-admin@example.com", UserRole.PLATFORM_ADMIN);

        mvc.perform(put("/api/v1/admin/identity-providers/APPLE")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"apple-client-id","clientSecret":"apple-client-secret"}"""))
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
