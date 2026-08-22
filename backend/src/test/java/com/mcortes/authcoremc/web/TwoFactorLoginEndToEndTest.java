package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.security.Totp;
import com.mcortes.authcoremc.service.TotpService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ticket 045, real end to end: real Redis (Testcontainers), real DB, real
 * {@code AuthenticationService}/{@code DirectTokenService}/{@code
 * TotpService}/{@code OtpService} — no mocks except the outbound email
 * sender (never actually send an email in a test). Proves the full
 * round trip the ticket's acceptance criteria describe for BOTH entry
 * points (password and social) and both 2FA methods (TOTP and OTP), plus
 * the pending token's real one-time-use guarantee — mirrors {@code
 * SocialExchangeEndToEndTest}'s pattern for the same reason: a mocked
 * {@code RedisTokenStore} can assert the guarantee but not really
 * demonstrate it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TwoFactorLoginEndToEndTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisTokenStore redisTokenStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TotpService totpService;

    @MockitoBean
    private EmailSender emailSender;

    private Tenant tenant;
    private IdentityClient firstPartyClient;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(new Tenant(
                "TwoFactorLogin-" + UUID.randomUUID(), "App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        firstPartyClient = identityClientRepository.save(new IdentityClient(
                tenant,
                "2fa-login-e2e-" + UUID.randomUUID(),
                null,
                true,
                List.of("https://acme.example.com/callback")));
    }

    private User totpUser() {
        User user = new User(
                tenant, "ada-" + UUID.randomUUID() + "@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("abcd1234"));
        user = userRepository.save(user);
        String secret = totpService.enroll(user);
        user.activateTwoFactorMethod(TwoFactorMethod.TOTP);
        userRepository.save(user);
        SecretHolder.set(user.getId(), secret);
        return user;
    }

    private User otpUser() {
        User user = new User(
                tenant, "ada-" + UUID.randomUUID() + "@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("abcd1234"));
        user = userRepository.save(user);
        user.activateTwoFactorMethod(TwoFactorMethod.OTP_EMAIL);
        userRepository.save(user);
        return user;
    }

    @Test
    void passwordLoginWithTotpIsBlockedUntilTheSecondFactorIsVerified() throws Exception {
        User user = totpUser();

        String pendingToken = login(user.getEmail(), "abcd1234")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.twoFactorRequired").value(true))
                .andExpect(jsonPath("$.method").value("TOTP"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = pendingTokenOf(pendingToken);

        String code = Totp.currentCode(SecretHolder.get(user.getId()));
        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + token + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.tokens.accessToken").exists())
                .andExpect(jsonPath("$.tokens.refreshToken").exists());
    }

    @Test
    void theSamePendingTokenCannotBeUsedTwice() throws Exception {
        User user = totpUser();
        String pendingToken =
                pendingTokenOf(login(user.getEmail(), "abcd1234").andReturn().getResponse().getContentAsString());
        String code = Totp.currentCode(SecretHolder.get(user.getId()));

        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        // Second attempt, even with a fresh valid TOTP code: the pending
        // token itself is already consumed.
        String freshCode = Totp.currentCode(SecretHolder.get(user.getId()));
        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\",\"code\":\"" + freshCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void aPendingTokenIssuedForOneClientIsRejectedByAnotherClient() throws Exception {
        User user = totpUser();
        IdentityClient otherClient = identityClientRepository.save(new IdentityClient(
                tenant, "2fa-login-e2e-other-" + UUID.randomUUID(), null, true, List.of()));
        String pendingToken =
                pendingTokenOf(login(user.getEmail(), "abcd1234").andReturn().getResponse().getContentAsString());
        String code = Totp.currentCode(SecretHolder.get(user.getId()));

        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", otherClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void aWrongCodeIsRejectedWithoutMintingTokens() throws Exception {
        User user = totpUser();
        String pendingToken =
                pendingTokenOf(login(user.getEmail(), "abcd1234").andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void socialLoginWithOtpEmailIsBlockedUntilTheSecondFactorIsVerified() throws Exception {
        User user = otpUser();
        String code = redisTokenStore.issue(
                SocialLoginSuccessHandler.EXCHANGE_PURPOSE, user.getId().toString(), Duration.ofSeconds(60));

        String body = mvc.perform(post("/api/v1/oauth2/social-exchange")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.twoFactorRequired").value(true))
                .andExpect(jsonPath("$.method").value("OTP_EMAIL"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String pendingToken = pendingTokenOf(body);

        // LoginCompletionService already sent the code via EmailSender — capture it.
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(user.getEmail()), any(), htmlCaptor.capture());
        String otpCode = htmlCaptor.getValue().replaceAll("\\D", "");

        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\",\"code\":\"" + otpCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.tokens.accessToken").exists());
    }

    @Test
    void aUserWithoutTwoFactorSeesNoChangeInEitherFlow() throws Exception {
        User user = userRepository.save(new User(
                tenant, "ada-" + UUID.randomUUID() + "@example.com", null, "Ada", "Lovelace",
                passwordEncoder.encode("abcd1234")));

        login(user.getEmail(), "abcd1234")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.accessToken").exists());

        String code = redisTokenStore.issue(
                SocialLoginSuccessHandler.EXCHANGE_PURPOSE, user.getId().toString(), Duration.ofSeconds(60));
        mvc.perform(post("/api/v1/oauth2/social-exchange")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.accessToken").exists());
    }

    // Ticket 046: POST /api/v1/login/2fa-resend, real Redis + real OtpService/EmailSender mock.

    @Test
    void resendingRightAfterLoginHitsTheRealCooldownButNeverDamagesThePendingToken() throws Exception {
        User user = otpUser();
        String pendingToken =
                pendingTokenOf(login(user.getEmail(), "abcd1234").andReturn().getResponse().getContentAsString());

        // LoginCompletionService already sent a code moments ago at login time —
        // OtpService's real 30s resend cooldown (unmocked here, unlike the
        // WebMvcTest suite) is still active, so this resend is rejected for
        // real, not swallowed like LoginCompletionService's own first send.
        mvc.perform(post("/api/v1/login/2fa-resend")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\"}"))
                .andExpect(status().isTooManyRequests());

        // The pendingToken itself must still be perfectly usable — a rejected
        // resend (peek, never consume) must not have damaged it. The original
        // code from login is still the valid one, since no fresh code replaced it.
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(user.getEmail()), any(), htmlCaptor.capture());
        String originalCode = htmlCaptor.getValue().replaceAll("\\D", "");

        mvc.perform(post("/api/v1/login/2fa-verify")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\",\"code\":\"" + originalCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()));
    }

    @Test
    void resendingForATotpUserIsANoOpEndToEnd() throws Exception {
        User user = totpUser();
        String pendingToken =
                pendingTokenOf(login(user.getEmail(), "abcd1234").andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/v1/login/2fa-resend")
                        .header("X-Client-Id", firstPartyClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\"}"))
                .andExpect(status().isAccepted());

        // TOTP never sends anything — the code already lives in the authenticator app.
        verify(emailSender, org.mockito.Mockito.never()).send(any(), any(), any());
    }

    @Test
    void resendIsRejectedByAMismatchedClientIdJustLikeVerify() throws Exception {
        User user = otpUser();
        IdentityClient otherClient = identityClientRepository.save(new IdentityClient(
                tenant, "2fa-login-e2e-other-" + UUID.randomUUID(), null, true, List.of()));
        String pendingToken =
                pendingTokenOf(login(user.getEmail(), "abcd1234").andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/v1/login/2fa-resend")
                        .header("X-Client-Id", otherClient.getClientId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingToken\":\"" + pendingToken + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String identifier, String password)
            throws Exception {
        return mvc.perform(post("/api/v1/login")
                .header("X-Client-Id", firstPartyClient.getClientId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}"));
    }

    private String pendingTokenOf(String responseBody) throws Exception {
        JsonNode json = objectMapper.readTree(responseBody);
        return json.get("pendingToken").asText();
    }

    /**
     * The real TOTP secret only exists as plain text for the instant {@code
     * TotpService#enroll} returns it — it's never stored or logged in plain
     * text again (see its Javadoc). This test-only holder keeps it around
     * just long enough to compute a real code with {@link Totp#currentCode}.
     */
    private static final class SecretHolder {
        private static final java.util.Map<UUID, String> SECRETS = new java.util.concurrent.ConcurrentHashMap<>();

        static void set(UUID userId, String secret) {
            SECRETS.put(userId, secret);
        }

        static String get(UUID userId) {
            return SECRETS.get(userId);
        }
    }
}
