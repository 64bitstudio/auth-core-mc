package com.mcortes.authcoremc.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.ExternalIdentityRepository;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.TokenPair;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ticket 063 — real end-to-end (JWT/DB reales) para las 2 piezas puramente
 * REST (leer cuentas conectadas, obtener la URL de vínculo). El flujo
 * completo Google/Facebook → callback → vínculo real está cubierto en
 * {@code SocialLoginSuccessHandlerTest} (simulando el `Authentication` que
 * Spring produciría, mismo patrón que ya usan los tests de login social —
 * no hay forma de automatizar el consentimiento real de un proveedor
 * externo en un test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountLinkProviderControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private DirectTokenService directTokenService;

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "LinkProvider-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "link-provider-e2e-" + UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
    }

    @Test
    void connectedProvidersReflectsRealExternalIdentityRows() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        externalIdentityRepository.save(
                new ExternalIdentity(firstPartyClient.getTenant(), user, IdentityProviderType.GOOGLE, "google-sub-1"));
        String accessToken = mintTokenFor(user);

        mvc.perform(get("/api/v1/account/connected-providers").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.provider == 'GOOGLE')].linked", org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.provider == 'FACEBOOK')].linked", org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void linkProviderReturnsARedirectUrlForTheRealRegistrationId() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/account/link-provider/google").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl")
                        .value("/oauth2/authorization/" + firstPartyClient.getId() + "::google"));
    }

    @Test
    void linkingAnUnsupportedProviderIsRejected() throws Exception {
        User user = userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/account/link-provider/apple").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/account/link-provider/not-a-real-provider")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(get("/api/v1/account/connected-providers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/account/link-provider/google")).andExpect(status().isUnauthorized());
    }

    private String mintTokenFor(User user) {
        TokenPair tokens = directTokenService.issueTokens(firstPartyClient, user);
        return tokens.accessToken();
    }
}
