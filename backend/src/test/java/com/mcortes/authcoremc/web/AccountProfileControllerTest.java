package com.mcortes.authcoremc.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

/**
 * Ticket 060 ("Mi Perfil", galgoth-studio) — real end-to-end igual que
 * {@link SetPasswordControllerTest}: JWT real, HTTP real, DB real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountProfileControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DirectTokenService directTokenService;

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "Profile-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "profile-e2e-" + UUID.randomUUID(), null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void getProfileReturnsNullCountryAndUsernameForABrandNewUser() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", null));
        String accessToken = mintTokenFor(user);

        mvc.perform(get("/api/v1/account/profile").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Ada"))
                .andExpect(jsonPath("$.country").doesNotExist())
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    void updatingProfileWithValidDataPersistsAndReflectsInSubsequentGet() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", null));
        String accessToken = mintTokenFor(user);

        mvc.perform(patch("/api/v1/account/profile")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ada\",\"apellidos\":\"Byron\",\"country\":\"México\",\"username\":\"ada_b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apellidos").value("Byron"))
                .andExpect(jsonPath("$.country").value("México"))
                .andExpect(jsonPath("$.username").value("ada_b"));

        mvc.perform(get("/api/v1/account/profile").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.username").value("ada_b"));
    }

    @Test
    void aUsernameAlreadyTakenInTheSameTenantIsRejectedWithoutOverwriting() throws Exception {
        userRepository.save(new User(firstPartyClient.getTenant(), "first@example.com", null, "Primero", "Uno", null));
        User secondUser = userRepository.save(new User(firstPartyClient.getTenant(), "second@example.com", null, "Segundo", "Dos", null));
        mvc.perform(patch("/api/v1/account/profile")
                .header("Authorization", "Bearer " + mintTokenFor(userRepository.findByTenantAndEmail(
                                firstPartyClient.getTenant(), "first@example.com").orElseThrow()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Primero\",\"apellidos\":\"Uno\",\"username\":\"tomado\"}"));

        mvc.perform(patch("/api/v1/account/profile")
                        .header("Authorization", "Bearer " + mintTokenFor(secondUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Segundo\",\"apellidos\":\"Dos\",\"username\":\"tomado\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_identifier"));

        User reloaded = userRepository.findById(secondUser.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getUsername()).isNull();
    }

    @Test
    void theSameUsernameInADifferentTenantDoesNotConflict() throws Exception {
        userRepository.save(new User(firstPartyClient.getTenant(), "first@example.com", null, "Primero", "Uno", null));
        mvc.perform(patch("/api/v1/account/profile")
                .header("Authorization", "Bearer " + mintTokenFor(userRepository.findByTenantAndEmail(
                                firstPartyClient.getTenant(), "first@example.com").orElseThrow()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Primero\",\"apellidos\":\"Uno\",\"username\":\"compartido\"}"));

        Tenant otherTenant = tenantRepository.save(new Tenant(
                "Profile-otro-" + UUID.randomUUID(), "Otra app", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        IdentityClient otherClient = identityClientRepository.save(new IdentityClient(
                otherTenant, "profile-otro-e2e-" + UUID.randomUUID(), null, true, List.of("https://otra.example.com/callback")));
        User otherTenantUser = userRepository.save(new User(otherTenant, "other@example.com", null, "Otro", "Usuario", null));
        TokenPair tokens = directTokenService.issueTokens(otherClient, otherTenantUser);

        mvc.perform(patch("/api/v1/account/profile")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Otro\",\"apellidos\":\"Usuario\",\"username\":\"compartido\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("compartido"));
    }

    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(get("/api/v1/account/profile")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ada\",\"apellidos\":\"Lovelace\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String mintTokenFor(User user) {
        TokenPair tokens = directTokenService.issueTokens(firstPartyClient, user);
        return tokens.accessToken();
    }
}
