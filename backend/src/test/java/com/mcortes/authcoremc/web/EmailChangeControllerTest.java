package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hallazgo real de seguridad (2026-09-15, ver docstring de {@link
 * EmailChangeController}): real end-to-end igual que {@code
 * AccountProfileControllerTest} -- JWT real, HTTP real, DB real -- en vez
 * del {@code @WebMvcTest} con {@code userId} en el body que este archivo
 * tenía antes del fix (ese patrón es exactamente lo que hacía explotable
 * el endpoint).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmailChangeControllerTest {

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

    @MockitoBean
    private EmailSender emailSender;

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "ChangeEmail-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "change-email-e2e-" + UUID.randomUUID(), null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void aValidRequestSendsAConfirmationToTheNewAddressAndReturns202() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/change-email/request")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"ada.new@example.com\"}"))
                .andExpect(status().isAccepted());

        verify(emailSender).send(org.mockito.ArgumentMatchers.eq("ada.new@example.com"), any(), any());
    }

    @Test
    void returns409WhenTheNewEmailIsAlreadyRegisteredInTheSameTenant() throws Exception {
        userRepository.save(new User(firstPartyClient.getTenant(), "taken@example.com", null, "Otro", "Usuario", "hash"));
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/change-email/request")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_identifier"));
    }

    // Hallazgo real que este test demuestra cerrado: antes del fix, un caller sin ningún
    // token podía mandar cualquier userId y disparar el cambio de correo de otra cuenta.
    @Test
    void isRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(post("/api/v1/change-email/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"attacker-controlled@example.com\"}"))
                .andExpect(status().isUnauthorized());

        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void confirmingWithAValidTokenChangesTheEmail() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/change-email/request")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"ada.new@example.com\"}"))
                .andExpect(status().isAccepted());

        String token = capturedToken();

        mvc.perform(post("/api/v1/change-email/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getEmail()).isEqualTo("ada.new@example.com");
    }

    @Test
    void confirmingWithAnInvalidTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/change-email/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\"}"))
                .andExpect(status().isBadRequest());
    }

    // Ticket 070's link factory embeds the token in the emailed link's query string
    // (?token=...) -- extracting it from the captured HTML body is the real integration
    // point a genuine confirmation click would use, not a shortcut around it.
    private String capturedToken() {
        org.mockito.ArgumentCaptor<String> htmlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(any(), any(), htmlCaptor.capture());
        String html = htmlCaptor.getValue();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("token=([^\"&]+)").matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("No token found in captured email HTML: " + html);
        }
        return matcher.group(1);
    }

    private String mintTokenFor(User user) {
        TokenPair tokens = directTokenService.issueTokens(firstPartyClient, user);
        return tokens.accessToken();
    }
}
