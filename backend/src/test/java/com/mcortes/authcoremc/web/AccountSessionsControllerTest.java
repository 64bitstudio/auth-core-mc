package com.mcortes.authcoremc.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Ticket 062 — real end-to-end. El login real (`/api/v1/login`) es lo que
 * dispara la captura del `User-Agent`, no una inserción directa vía
 * repositorio — así se prueba el cableado real, no solo el servicio en
 * aislamiento.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountSessionsControllerTest {

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
                "Sessions-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "sessions-e2e-" + UUID.randomUUID(), null, true, List.of("https://acme.example.com/callback")));
    }

    private String loginAndGetAccessToken(String email, String userAgent) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", email, "password", "original123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get("tokens")
                .get("accessToken")
                .asText();
    }

    private String loginAndGetRefreshToken(String email, String userAgent) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", email, "password", "original123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get("tokens")
                .get("refreshToken")
                .asText();
    }

    @Test
    void loggingInFromTwoDevicesShowsBothWithParsedBrowserAndOs() throws Exception {
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));

        String chromeAccess = loginAndGetAccessToken(
                "ada@example.com",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36");
        mvc.perform(post("/api/v1/login")
                .header("X-Client-Id", firstPartyClient.getClientId())
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("identifier", "ada@example.com", "password", "original123"))));

        mvc.perform(get("/api/v1/account/sessions").header("Authorization", "Bearer " + chromeAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[*].browser", org.hamcrest.Matchers.hasItems("Chrome", "Safari")))
                .andExpect(jsonPath("$[*].os", org.hamcrest.Matchers.hasItems("macOS", "iOS")));
    }

    @Test
    void theSessionMatchingTheSuppliedRefreshTokenIsMarkedCurrent() throws Exception {
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String refreshToken = loginAndGetRefreshToken("ada@example.com", "UA-1");
        String accessToken = loginAndGetAccessToken("ada@example.com", "UA-2");

        mvc.perform(get("/api/v1/account/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Current-Refresh-Token", refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.current == true)]", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void revokingASessionMakesItsRefreshTokenStopWorking() throws Exception {
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String refreshToken = loginAndGetRefreshToken("ada@example.com", "UA-1");
        String accessToken = loginAndGetAccessToken("ada@example.com", "UA-2");

        String sessionId = mapper.readTree(mvc.perform(get("/api/v1/account/sessions")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Current-Refresh-Token", refreshToken))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get(1) // el que NO es "current" -- la sesión del refreshToken que vamos a revocar
                .get("id")
                .asText();

        mvc.perform(delete("/api/v1/account/sessions/{id}", sessionId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokingASessionThatBelongsToAnotherUserIsRejected() throws Exception {
        User owner = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        User intruder = userRepository.save(new User(
                firstPartyClient.getTenant(), "intruder@example.com", null, "In", "Truder", passwordEncoder.encode("original123")));
        TokenPair ownerTokens = directTokenService.issueTokens(firstPartyClient, owner, "UA-owner");
        String intruderAccess = directTokenService.issueTokens(firstPartyClient, intruder, "UA-intruder").accessToken();

        String ownerSessionId = mapper.readTree(mvc.perform(get("/api/v1/account/sessions")
                        .header("Authorization", "Bearer " + ownerTokens.accessToken()))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get(0)
                .get("id")
                .asText();

        mvc.perform(delete("/api/v1/account/sessions/{id}", ownerSessionId).header("Authorization", "Bearer " + intruderAccess))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokeOthersLeavesOnlyTheCurrentSessionUsable() throws Exception {
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String currentRefreshToken = loginAndGetRefreshToken("ada@example.com", "UA-current");
        String otherRefreshToken = loginAndGetRefreshToken("ada@example.com", "UA-other");
        String accessToken = loginAndGetAccessToken("ada@example.com", "UA-current");

        mvc.perform(post("/api/v1/account/sessions/revoke-others")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Current-Refresh-Token", currentRefreshToken))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + otherRefreshToken + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + currentRefreshToken + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(get("/api/v1/account/sessions")).andExpect(status().isUnauthorized());
    }

}
