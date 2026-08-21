package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.LoginOutcome;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Ticket 015, end to end: a real login attempt (success and failure) through
 * the real HTTP endpoint actually inserts a real row in {@code
 * login_event} — not a mock verifying the controller called the recorder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginEventRecordingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private LoginEventRepository loginEventRepository;

    private String clientId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        clientId = "acme-login-event-test-" + UUID.randomUUID();
        tenant = tenantRepository.save(
                // Ticket 013 added a UNIQUE constraint on tenant.name — a fixed name here
                // collided with itself across @BeforeEach runs of different @Test methods.
                new Tenant("Acme LoginEvent " + UUID.randomUUID(), "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        identityClientRepository.save(
                new IdentityClient(tenant, clientId, null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void aSuccessfulLoginRecordsASuccessLoginEvent() throws Exception {
        registerUser("ada@example.com", "abcd1234");

        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ada@example.com\",\"password\":\"abcd1234\"}"))
                .andExpect(status().isOk());

        var events = loginEventRepository.findAll().stream()
                .filter(e -> e.getTenant().getId().equals(tenant.getId()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo(LoginOutcome.SUCCESS);
        assertThat(events.get(0).getProvider()).isEqualTo("PASSWORD");
        assertThat(events.get(0).getUser()).isNotNull();
        assertThat(events.get(0).getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aFailedLoginRecordsAFailureLoginEventWithNoUser() throws Exception {
        registerUser("grace@example.com", "abcd1234");

        mvc.perform(post("/api/v1/login")
                        .header("X-Client-Id", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"grace@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        var events = loginEventRepository.findAll().stream()
                .filter(e -> e.getTenant().getId().equals(tenant.getId()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo(LoginOutcome.FAILURE);
        assertThat(events.get(0).getUser()).isNull();
    }

    private void registerUser(String email, String password) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mvc.perform(post("/api/v1/register")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        java.util.Map.of("email", email, "nombre", "Test", "apellidos", "User", "password", password))));
    }
}
