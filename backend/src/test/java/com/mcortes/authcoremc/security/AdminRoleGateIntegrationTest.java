package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ticket 012, end to end: proves real production wiring, not mocks — a real
 * login through {@code /api/v1/login} mints a real signed access token via
 * the actually-configured {@code JwtGenerator} bean (with {@code
 * AdminClaimsCustomizer} wired in {@code TokenGeneratorConfig}), that token
 * authenticates a real Bearer request against the resource server config
 * this ticket added to {@code SecurityConfig}, against an endpoint that
 * already existed before this ticket ({@code IdentityProviderController},
 * ticket 006) rather than a new admin route (none exist yet — ticket 013+).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminRoleGateIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    // Real HTTP round-trips through the running server don't share this
    // test's own transaction, so per-test data must be unique rather than
    // relying on rollback between tests — client_id and email both have
    // real UNIQUE constraints.
    private String clientId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        clientId = "acme-integration-test-" + java.util.UUID.randomUUID();
        tenant = tenantRepository.save(
                // Ticket 013 added a UNIQUE constraint on tenant.name — a fixed name here
                // collided with itself across @BeforeEach runs of different @Test methods.
                new Tenant("Acme Integration " + UUID.randomUUID(), "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        identityClientRepository.save(
                new IdentityClient(tenant, clientId, null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void aRequestWithNoBearerTokenIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/v1/identity-providers").header("X-Client-Id", clientId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRealAccessTokenFromARealLoginAuthenticatesAProtectedRequest() throws Exception {
        registerUser("ada@example.com", "abcd1234");
        grantRole("ada@example.com", UserRole.TENANT_ADMIN);
        String accessToken = login("ada@example.com", "abcd1234");

        mvc.perform(get("/api/v1/identity-providers")
                        .header("X-Client-Id", clientId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void theRealIssuedTokenCarriesTheGrantedRoleAndTenantIdClaims() throws Exception {
        registerUser("grace@example.com", "abcd1234");
        grantRole("grace@example.com", UserRole.PLATFORM_ADMIN);
        String accessToken = login("grace@example.com", "abcd1234");

        var claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
        assertThat(claims.getStringClaim("role")).isEqualTo("PLATFORM_ADMIN");
        assertThat(claims.getStringClaim("tenant_id")).isEqualTo(tenant.getId().toString());
    }

    private void registerUser(String email, String password) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mvc.perform(post("/api/v1/register")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "email", email, "nombre", "Test", "apellidos", "User", "password", password))));
    }

    private void grantRole(String email, UserRole role) {
        User user = userRepository.findByTenantAndEmail(tenant, email).orElseThrow();
        user.grantRole(role);
        userRepository.save(user);
    }

    private String login(String identifier, String password) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String responseBody = mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", identifier, "password", password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return (String) ((Map<?, ?>) mapper.readValue(responseBody, Map.class).get("tokens")).get("accessToken");
    }
}
