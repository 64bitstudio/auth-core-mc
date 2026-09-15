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
 * Ticket 093 de galgoth-studio, tercer hallazgo real de verificación en
 * vivo: es la primera vez que un flujo {@code oauth2Login} real se prueba
 * en un navegador de verdad, detrás de Traefik (todos los tests previos de
 * login social pasan por MockMvc directo, sin proxy de por medio).
 * Traefik termina TLS y reenvía por HTTP plano con {@code X-Forwarded-
 * Proto: https} -- sin {@code server.forward-headers-strategy=framework}
 * (solo en el perfil {@code deploy}, ver {@code application-deploy.
 * properties}), Spring construye el {@code redirect_uri} de OAuth2 con el
 * esquema INTERNO ({@code http://}), que Google rechaza con {@code
 * redirect_uri_mismatch} porque no coincide con lo registrado en Google
 * Cloud Console.
 *
 * <p>{@link TenantSecretEncryptor} mockeado a propósito -- lo que este
 * test prueba es la resolución de esquema/host de
 * {@code OAuth2AuthorizationRequestRedirectFilter} bajo {@code
 * X-Forwarded-*}, no el envelope encryption real (ya cubierto por
 * {@code AdminIdentityProviderEndToEndTest}); evita el costo de un
 * Vault de verdad para esto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ForwardedHeadersOAuth2RedirectIntegrationTest {

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
    void withForwardHeadersStrategyDisabledByDefaultTheRedirectUriUsesTheInternalHttpScheme() throws Exception {
        // Sin server.forward-headers-strategy=framework (el default fuera del
        // perfil deploy, y el que aplica en este test) -- reproduce el bug
        // exacto que rompía "Conectar" antes del fix.
        IdentityClient client = configuredGoogleClient();

        MvcResult result = mvc.perform(get("/oauth2/authorization/" + client.getId() + "::google")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "auth-dev.64bitstudio.com"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirectUri = extractRedirectUri(result);
        assertThat(redirectUri).startsWith("http://");
    }

    private IdentityClient configuredGoogleClient() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "ForwardedHeaders-" + java.util.UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600,
                300));
        IdentityClient client = identityClientRepository.save(new IdentityClient(
                tenant, "forwarded-headers-e2e-" + java.util.UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
        TenantIdentityProvider provider = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        provider.configure("real-google-client-id", "encrypted-secret");
        tenantIdentityProviderRepository.save(provider);
        when(tenantSecretEncryptor.decrypt(any(), any())).thenReturn("decrypted-secret");
        return client;
    }

    private static String extractRedirectUri(MvcResult result) {
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String marker = "redirect_uri=";
        int start = location.indexOf(marker) + marker.length();
        int end = location.indexOf('&', start);
        String encoded = end == -1 ? location.substring(start) : location.substring(start, end);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
