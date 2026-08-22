package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Ticket 041 (HU-5), real end to end: real Bearer JWT, real HTTP requests,
 * real DB — no mocks. Social login itself isn't wired yet (tickets
 * 038-040), so a social-only account (no {@code password_hash}) is created
 * directly here and its session token minted the same way a real social
 * login eventually will — through {@link DirectTokenService}, "the same
 * minter as /api/v1/login" (see docs/definiciones/login-social-real.md
 * §5) — rather than through the not-yet-existing social callback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SetPasswordControllerTest {

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
                "SetPassword-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "set-password-e2e-" + UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
    }

    @Test
    void aSocialOnlyAccountCanSetItsFirstPasswordAndThenLogInWithIt() throws Exception {
        User socialOnlyUser = userRepository.save(
                new User(firstPartyClient.getTenant(), "social-only@example.com", null, "Ada", "Lovelace", null));
        String accessToken = mintTokenFor(socialOnlyUser);

        mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true));

        User reloaded = userRepository.findById(socialOnlyUser.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNotNull();

        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("identifier", "social-only@example.com", "password", "newpass123"))))
                .andExpect(status().isOk());
    }

    @Test
    void settingAPasswordWhenOneAlreadyExistsIsRejectedWithoutOverwritingIt() throws Exception {
        User existingPasswordUser = new User(
                firstPartyClient.getTenant(), "already-has-password@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("original123"));
        existingPasswordUser = userRepository.save(existingPasswordUser);
        String accessToken = mintTokenFor(existingPasswordUser);

        mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"attemptedTakeover123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("password_already_set"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        // The original password still works — proof it was never overwritten.
        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("identifier", "already-has-password@example.com", "password", "original123"))))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAWeakNewPasswordWithAClearMessage() throws Exception {
        User socialOnlyUser = userRepository.save(
                new User(firstPartyClient.getTenant(), "weak-password@example.com", null, "Ada", "Lovelace", null));
        String accessToken = mintTokenFor(socialOnlyUser);

        mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("weak_password"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        User reloaded = userRepository.findById(socialOnlyUser.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNull();
    }

    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(post("/api/v1/account/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String mintTokenFor(User user) {
        TokenPair tokens = directTokenService.issueTokens(firstPartyClient, user);
        return tokens.accessToken();
    }
}
