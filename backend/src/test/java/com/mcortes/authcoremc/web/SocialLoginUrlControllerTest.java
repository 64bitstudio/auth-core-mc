package com.mcortes.authcoremc.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
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
 * Ticket 072 -- real end-to-end (DB real, sin JWT: este endpoint es
 * público a propósito, ver Javadoc de {@link SocialLoginUrlController}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SocialLoginUrlControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "SocialLoginUrl-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "social-login-url-e2e-" + UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
    }

    @Test
    void returnsAnAbsoluteRedirectUrlForTheRealRegistrationIdWithoutAnyBearerToken() throws Exception {
        mvc.perform(get("/api/v1/oauth2/login-url/google").header("X-Client-Id", firstPartyClient.getClientId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl")
                        .value("http://localhost:8080/oauth2/authorization/" + firstPartyClient.getId() + "::google"));
    }

    @Test
    void worksForFacebookToo() throws Exception {
        mvc.perform(get("/api/v1/oauth2/login-url/facebook").header("X-Client-Id", firstPartyClient.getClientId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl")
                        .value("http://localhost:8080/oauth2/authorization/" + firstPartyClient.getId() + "::facebook"));
    }

    @Test
    void anUnsupportedProviderIsRejected() throws Exception {
        mvc.perform(get("/api/v1/oauth2/login-url/apple").header("X-Client-Id", firstPartyClient.getClientId()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/oauth2/login-url/not-a-real-provider")
                        .header("X-Client-Id", firstPartyClient.getClientId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownClientIdIsRejected() throws Exception {
        mvc.perform(get("/api/v1/oauth2/login-url/google").header("X-Client-Id", "not-a-real-client"))
                .andExpect(status().isUnauthorized());
    }
}
