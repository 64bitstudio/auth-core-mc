package com.mcortes.authcoremc.web;

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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Ticket 070 -- pipeline completo, en su propio contexto de Spring (ver
 * {@code @DynamicPropertySource}) para no obligar a
 * {@code AccountSessionsControllerTest} a cargar una base de datos
 * GeoLite2 real: login real → `getRemoteAddr()` simulado (ver
 * {@code .with(...)} más abajo, MockMvc no pasa por el `RemoteIpValve`
 * real de Tomcat -- ese mecanismo de `server.forward-headers-strategy=
 * native` ya es de Spring Boot/Tomcat, no algo de este proyecto que haga
 * falta re-probar) → `GET /api/v1/account/sessions` refleja ciudad/país
 * reales resueltos por {@code GeoIpService} contra la base de datos de
 * prueba oficial de MaxMind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountSessionsGeoIpIntegrationTest {

    @DynamicPropertySource
    static void geoIpDatabase(DynamicPropertyRegistry registry) throws URISyntaxException {
        Path path = Paths.get(AccountSessionsGeoIpIntegrationTest.class
                .getClassLoader()
                .getResource("geoip/GeoLite2-City-Test.mmdb")
                .toURI());
        registry.add("geoip.database-path", path::toString);
    }

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

    private final ObjectMapper mapper = new ObjectMapper();

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "SessionsGeoIp-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "sessions-geoip-e2e-" + UUID.randomUUID(), null, true,
                List.of("https://acme.example.com/callback")));
    }

    @Test
    void aLoginFromAKnownIpShowsARealCityAndCountryInSessions() throws Exception {
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("original123")));

        // 81.2.69.142 -- London, England, GB, uno de los IPs de ejemplo que MaxMind documenta para su base de prueba (ver GeoIpServiceTest).
        String accessToken = mapper.readTree(mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .with(request -> {
                            request.setRemoteAddr("81.2.69.142");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("identifier", "ada@example.com", "password", "original123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get("tokens")
                .get("accessToken")
                .asText();

        mvc.perform(get("/api/v1/account/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].city").value("London"))
                .andExpect(jsonPath("$[0].country").value("United Kingdom"));
    }

    @Test
    void aLoginWithoutARealClientIpShowsNoLocationInsteadOfFailing() throws Exception {
        userRepository.save(new User(
                firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("original123")));

        // Sin .with(...) -- MockMvc usa su remoteAddr simulado por defecto (127.0.0.1), que GeoLite2 nunca resuelve.
        String accessToken = mapper.readTree(mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("identifier", "ada@example.com", "password", "original123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
                .get("tokens")
                .get("accessToken")
                .asText();

        mvc.perform(get("/api/v1/account/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].city").doesNotExist())
                .andExpect(jsonPath("$[0].browser").exists());
    }
}
