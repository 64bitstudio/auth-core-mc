package com.mcortes.authcoremc.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.TokenPair;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Ticket 061 — real end-to-end, mismo patrón que {@link SetPasswordControllerTest}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChangePasswordControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DirectTokenService directTokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "ChangePassword-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "change-password-e2e-" + UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
    }

    @Test
    void correctCurrentPasswordAndValidNewPasswordChangesIt() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("original123")));
        String accessToken = mintTokenFor(user);

        mvc.perform(patch("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"original123\",\"newPassword\":\"brandnew123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true));

        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", "ada@example.com", "password", "brandnew123"))))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordIsRejectedWithoutChangingAnything() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("original123")));
        String accessToken = mintTokenFor(user);

        mvc.perform(patch("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrongpass123\",\"newPassword\":\"brandnew123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("incorrect_current_password"));

        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", "ada@example.com", "password", "original123"))))
                .andExpect(status().isOk());
    }

    @Test
    void aWeakNewPasswordIsRejectedWithoutChangingAnything() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("original123")));
        String accessToken = mintTokenFor(user);

        mvc.perform(patch("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"original123\",\"newPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("weak_password"));
    }

    @Test
    void aSocialOnlyAccountWithoutAPasswordGetsADistinctError() throws Exception {
        User socialOnlyUser = userRepository.save(
                new User(firstPartyClient.getTenant(), "social@example.com", null, "Ada", "Lovelace", null));
        String accessToken = mintTokenFor(socialOnlyUser);

        mvc.perform(patch("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"anything123\",\"newPassword\":\"brandnew123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_password_set"));
    }

    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(patch("/api/v1/account/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String mintTokenFor(User user) {
        TokenPair tokens = directTokenService.issueTokens(firstPartyClient, user);
        return tokens.accessToken();
    }
}
