package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.Totp;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hallazgo real de seguridad (2026-09-15, ver docstring de {@link
 * TwoFactorController} -- el más grave de los 3 controllers corregidos):
 * real end-to-end, mismo criterio que {@code AccountProfileControllerTest}.
 * {@code totp/enroll} sin autenticación permitía enrolar el secreto DEL
 * ATACANTE en la cuenta de otro usuario y activarlo -- los tests de abajo
 * demuestran que el flujo completo (enroll → verify → activate) ahora
 * exige el JWT de la propia cuenta en cada paso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TwoFactorControllerTest {

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
    private StringRedisTemplate redis;

    @MockitoBean
    private EmailSender emailSender;

    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant(
                "TwoFactor-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant, "2fa-e2e-" + UUID.randomUUID(), null, true, List.of("https://acme.example.com/callback")));
    }

    @Test
    void otpRequestSendsACodeToTheCallersOwnEmailAndReturns202() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/2fa/otp/request").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isAccepted());

        verify(emailSender).send(org.mockito.ArgumentMatchers.eq("ada@example.com"), any(), any());
    }

    @Test
    void otpRequestIsRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(post("/api/v1/2fa/otp/request")).andExpect(status().isUnauthorized());

        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void otpVerifySucceedsWithTheRealCodeJustSent() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);
        mvc.perform(post("/api/v1/2fa/otp/request").header("Authorization", "Bearer " + accessToken));
        String code = redis.opsForValue().get("otp:" + user.getId());

        mvc.perform(post("/api/v1/2fa/otp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void otpVerifyRejectsAWrongCode() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);
        mvc.perform(post("/api/v1/2fa/otp/request").header("Authorization", "Bearer " + accessToken));

        mvc.perform(post("/api/v1/2fa/otp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enrollingTotpPersistsTheSecretOnTheCallersOwnAccountAndReturnsIt() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/2fa/totp/enroll").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTotpSecretEncrypted()).isNotNull();
    }

    // El hallazgo real que este test demuestra cerrado: antes del fix, este mismo POST
    // sin Authorization habría enrolado el secreto EN LA CUENTA de cualquier userId que
    // el atacante pusiera en el body -- ya no hay ningún userId en el body que aceptar.
    @Test
    void enrollingTotpIsRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(post("/api/v1/2fa/totp/enroll")).andExpect(status().isUnauthorized());
    }

    @Test
    void totpVerifySucceedsWithACodeGeneratedFromTheEnrolledSecret() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);
        String enrollResponse = mvc.perform(post("/api/v1/2fa/totp/enroll").header("Authorization", "Bearer " + accessToken))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secret = extractSecret(enrollResponse);

        mvc.perform(post("/api/v1/2fa/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + Totp.currentCode(secret) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void activatingAMethodPersistsItOnTheCallersOwnAccount() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/2fa/method")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"OTP_EMAIL\"}"))
                .andExpect(status().isOk());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTwoFactorMethod()).isEqualTo(TwoFactorMethod.OTP_EMAIL);
    }

    // El hallazgo real más grave de los 3: sin este fix, cualquiera podía activar TOTP
    // en la cuenta de otra persona (con SU PROPIO secreto ya enrolado ahí mismo) y
    // dejarla bloqueada afuera permanentemente, sin poseer nada que la víctima controle.
    @Test
    void activatingTotpWithoutHavingEnrolledItFirstIsRejected() throws Exception {
        User user = userRepository.save(new User(firstPartyClient.getTenant(), "ada@example.com", null, "Ada", "Lovelace", "hash"));
        String accessToken = mintTokenFor(user);

        mvc.perform(post("/api/v1/2fa/method")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"TOTP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activatingAMethodIsRejectedWithoutAValidBearerToken() throws Exception {
        mvc.perform(post("/api/v1/2fa/method")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"OTP_EMAIL\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String mintTokenFor(User user) {
        TokenPair tokens = directTokenService.issueTokens(firstPartyClient, user);
        return tokens.accessToken();
    }

    // Sin `.*` alrededor del grupo (hallazgo real de Sonar, S8786: backtracking
    // súper-lineal) -- basta con encontrar el primer match de la clave literal.
    private static String extractSecret(String json) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"secret\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("No secret found in enroll response: " + json);
        }
        return matcher.group(1);
    }
}
