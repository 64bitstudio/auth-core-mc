package com.mcortes.authcoremc.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ticket 038, real end to end: real Redis (Testcontainers), real DB, real
 * {@code DirectTokenService} — no mocks. Proves the actual one-time-use
 * guarantee {@code RedisTokenStore.consume(...)} gives ("un segundo intento
 * con el mismo código falla explícitamente", the ticket's own wording) —
 * something a mocked {@code RedisTokenStore} (see
 * {@link SocialExchangeControllerTest}) can assert but not really
 * demonstrate. Mirrors {@code SetPasswordControllerTest}'s pattern for the
 * same reason: social login's own redirect isn't wired end-to-end yet
 * (ticket 039), so the one-time code is minted directly here the same way
 * {@code SocialLoginSuccessHandler} does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SocialExchangeEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisTokenStore redisTokenStore;

    private IdentityClient firstPartyClient;
    private User user;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "SocialExchange-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant,
                "social-exchange-e2e-" + UUID.randomUUID(),
                null,
                true,
                List.of("https://acme.example.com/callback")));
        user = userRepository.save(new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null));
    }

    @Test
    void exchangesAValidCodeForRealTokensWithTheSameShapeAsLogin() throws Exception {
        String code = redisTokenStore.issue(
                SocialLoginSuccessHandler.EXCHANGE_PURPOSE, user.getId().toString(), Duration.ofSeconds(60));

        mvc.perform(post("/api/v1/oauth2/social-exchange")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.user.hasPassword").value(false))
                .andExpect(jsonPath("$.tokens.accessToken").exists())
                .andExpect(jsonPath("$.tokens.refreshToken").exists())
                .andExpect(jsonPath("$.tokens.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.tokens.expiresInSeconds").exists());
    }

    @Test
    void aSecondAttemptWithTheSameCodeFailsExplicitly() throws Exception {
        String code = redisTokenStore.issue(
                SocialLoginSuccessHandler.EXCHANGE_PURPOSE, user.getId().toString(), Duration.ofSeconds(60));

        mvc.perform(post("/api/v1/oauth2/social-exchange")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/oauth2/social-exchange")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void anUnknownCodeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/oauth2/social-exchange")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"does-not-exist\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }
}
