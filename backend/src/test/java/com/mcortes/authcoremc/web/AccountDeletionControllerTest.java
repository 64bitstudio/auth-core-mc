package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.AccountDeletionFailedException;
import com.mcortes.authcoremc.service.GalgothStudioPurgeClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Ticket 064 — real end-to-end (login real, JWT real) salvo
 * {@link GalgothStudioPurgeClient}, mockeado para no depender de una
 * instancia real de galgoth-studio en CI: el propio cliente ya tiene su
 * cobertura de "falla ruidoso sin configurar" en
 * {@code GalgothStudioPurgeClientTest}, lo que importa probar acá es la
 * ORQUESTACIÓN (orden estricto, aborta la operación entera si la purga falla).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountDeletionControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private GalgothStudioPurgeClient galgothStudioPurgeClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "Deletion-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "deletion-e2e-" + UUID.randomUUID(), null, true, List.of("https://acme.example.com/callback")));
    }

    private String login(String email) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", email, "password", "original123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get("tokens")
                .get("accessToken")
                .asText();
    }

    private void expectLoginRejectedAsDeactivated(String email) throws Exception {
        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", email, "password", "original123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingTheAccountPurgesGalgothStudioThenDeactivatesTheUserAndRejectsFutureLogins() throws Exception {
        doNothing().when(galgothStudioPurgeClient).purgeProjects(any());
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String accessToken = login("ada@example.com");

        mvc.perform(delete("/api/v1/account")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("confirmIdentifier", "ada@example.com"))))
                .andExpect(status().isNoContent());

        verify(galgothStudioPurgeClient).purgeProjects(user.getId());
        expectLoginRejectedAsDeactivated("ada@example.com");
        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void deletingTheAccountRevokesAllOfItsRefreshTokens() throws Exception {
        doNothing().when(galgothStudioPurgeClient).purgeProjects(any());
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String refreshToken = mapper.readTree(mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", "ada@example.com", "password", "original123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get("tokens")
                .get("refreshToken")
                .asText();
        String accessToken = login("ada@example.com");

        mvc.perform(delete("/api/v1/account")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("confirmIdentifier", "ada@example.com"))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWrongConfirmIdentifierIsRejectedAndNothingIsPurgedOrChanged() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String accessToken = login("ada@example.com");

        mvc.perform(delete("/api/v1/account")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("confirmIdentifier", "no-soy-yo@example.com"))))
                .andExpect(status().isBadRequest());

        verify(galgothStudioPurgeClient, never()).purgeProjects(any());
        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isTrue();
    }

    // Ticket 064 -- AC explícito: si la purga de galgoth-studio falla, la cuenta NUNCA queda desactivada.
    @Test
    void whenGalgothStudioPurgeFailsTheAccountIsNotDeactivatedAndCanStillLogIn() throws Exception {
        doThrow(new AccountDeletionFailedException("boom")).when(galgothStudioPurgeClient).purgeProjects(any());
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", passwordEncoder.encode("original123")));
        String accessToken = login("ada@example.com");

        mvc.perform(delete("/api/v1/account")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("confirmIdentifier", "ada@example.com"))))
                .andExpect(status().isInternalServerError());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isTrue();
        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("identifier", "ada@example.com", "password", "original123"))))
                .andExpect(status().isOk());
    }

    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(delete("/api/v1/account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("confirmIdentifier", "ada@example.com"))))
                .andExpect(status().isUnauthorized());
    }
}
