package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The fixed counterpart of {@link ForwardedHeadersOAuth2RedirectIntegrationTest}:
 * same exact request, only difference is {@code server.forward-headers-strategy=
 * framework} being active (the value {@code application-deploy.properties} sets
 * for the 3 real deployments, never for local/tests otherwise) — proves the
 * property is what fixes the real "Conectar" bug, not a coincidence of test
 * setup.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ForwardedHeadersOAuth2RedirectFixedIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private TenantIdentityProviderRepository tenantIdentityProviderRepository;

    @MockitoBean
    private TenantSecretEncryptor tenantSecretEncryptor;

    @Test
    void withForwardedHeadersStrategyEnabledTheRedirectUriUsesTheExternalHttpsScheme() throws Exception {
        IdentityClient client = configuredGoogleClient();

        MvcResult result = mvc.perform(get("/oauth2/authorization/" + client.getId() + "::google")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "auth-dev.64bitstudio.com"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirectUri = extractRedirectUri(result);
        assertThat(redirectUri).isEqualTo("https://auth-dev.64bitstudio.com/login/oauth2/code/" + client.getId() + "::google");
    }

    private IdentityClient configuredGoogleClient() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "ForwardedHeadersFixed-" + java.util.UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400,
                3_600, 300));
        IdentityClient client = identityClientRepository.save(new IdentityClient(
                tenant, "forwarded-headers-fixed-e2e-" + java.util.UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
        TenantIdentityProvider provider = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        provider.configure("real-google-client-id", "encrypted-secret");
        tenantIdentityProviderRepository.save(provider);
        when(tenantSecretEncryptor.decrypt(any(), any())).thenReturn("decrypted-secret");
        return client;
    }

    private static String extractRedirectUri(MvcResult result) throws Exception {
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String marker = "redirect_uri=";
        int start = location.indexOf(marker) + marker.length();
        int end = location.indexOf('&', start);
        String encoded = end == -1 ? location.substring(start) : location.substring(start, end);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
