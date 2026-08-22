package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SocialLoginFailureHandler;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.LoginCompletionService;
import com.mcortes.authcoremc.service.OtpService;
import com.mcortes.authcoremc.service.TokenPair;
import com.mcortes.authcoremc.service.TooManyAttemptsException;
import com.mcortes.authcoremc.service.TotpService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// See RegistrationControllerTest for why SecurityConfig must be imported explicitly here.
@WebMvcTest(TwoFactorLoginController.class)
@Import(SecurityConfig.class)
class TwoFactorLoginControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean
    private SocialLoginSuccessHandler socialLoginSuccessHandler;

    @MockitoBean
    private SocialLoginFailureHandler socialLoginFailureHandler;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private RedisTokenStore redisTokenStore;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TotpService totpService;

    @MockitoBean
    private OtpService otpService;

    @MockitoBean
    private DirectTokenService directTokenService;

    private final Tenant tenant = tenantFixture();
    private final IdentityClient firstPartyClient = clientFixture(tenant, "acme-web-app");

    private static Tenant tenantFixture() {
        Tenant t = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    private static IdentityClient clientFixture(Tenant tenant, String clientId) {
        IdentityClient client = new IdentityClient(tenant, clientId, null, true, List.of());
        ReflectionTestUtils.setField(client, "id", UUID.randomUUID());
        return client;
    }

    private static User userFixture(Tenant tenant, TwoFactorMethod method) {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        if (method == TwoFactorMethod.TOTP) {
            user.enrollTotpSecret("encrypted-secret");
        }
        if (method != TwoFactorMethod.NONE) {
            user.activateTwoFactorMethod(method);
        }
        return user;
    }

    @Test
    void verifiesATotpCodeAndMintsTokens() {
        User user = userFixture(tenant, TwoFactorMethod.TOTP);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(directTokenService.issueTokens(firstPartyClient, user))
                .thenReturn(new TokenPair("jwt-access-token", "opaque-refresh-token", "Bearer", 900));

        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc","code":"123456"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("ada@example.com")
                .contains("jwt-access-token")
                .contains("opaque-refresh-token");

        verify(totpService).verify(user, "123456");
        verify(otpService, never()).verifyOtp(any(), any());
    }

    @Test
    void verifiesAnOtpCodeAndMintsTokens() {
        User user = userFixture(tenant, TwoFactorMethod.OTP_EMAIL);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(directTokenService.issueTokens(firstPartyClient, user))
                .thenReturn(new TokenPair("jwt-access-token", "opaque-refresh-token", "Bearer", 900));

        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc","code":"654321"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(otpService).verifyOtp(user, "654321");
        verify(totpService, never()).verify(any(), any());
    }

    @Test
    void returns400ForAnUnknownOrAlreadyUsedPendingToken() {
        when(redisTokenStore.consume(LoginCompletionService.PENDING_2FA_PURPOSE, "bad-token"))
                .thenReturn(Optional.empty());

        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"bad-token","code":"123456"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        verify(userRepository, never()).findById(any());
        verify(directTokenService, never()).issueTokens(any(), any());
    }

    @Test
    void returns400WhenTheXClientIdDoesNotMatchThePendingTokensClient() {
        User user = userFixture(tenant, TwoFactorMethod.TOTP);
        when(redisTokenStore.consume(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("some-other-client::" + user.getId()));

        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc","code":"123456"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        // Never even resolves the client or looks up the user for a mismatched
        // pending token — same "fail before touching anything else" pattern.
        verify(clientContextResolver, never()).resolveClient(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void returns400ForAWrongTotpCode() {
        User user = userFixture(tenant, TwoFactorMethod.TOTP);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        doThrow(new InvalidTokenException("Invalid or expired code")).when(totpService).verify(user, "000000");

        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc","code":"000000"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        verify(directTokenService, never()).issueTokens(any(), any());
    }

    @Test
    void returns429WhenOtpGuessingIsRateLimited() {
        User user = userFixture(tenant, TwoFactorMethod.OTP_EMAIL);
        when(clientContextResolver.resolveClient("acme-web-app")).thenReturn(firstPartyClient);
        when(redisTokenStore.consume(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        doThrow(new TooManyAttemptsException("blocked")).when(otpService).verifyOtp(user, "000000");

        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc","code":"000000"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(429);
    }

    @Test
    void aMissingClientIdHeaderIsAPlain400NotA401() {
        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc","code":"123456"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .doesNotContainHeader("WWW-Authenticate");
    }

    @Test
    void returns400WhenTheCodeFieldIsMissing() {
        mvc.post()
                .uri("/api/v1/login/2fa-verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("validation_error");
    }

    // Ticket 046: POST /api/v1/login/2fa-resend.

    @Test
    void resendsAnOtpCodeForOtpEmailWithoutConsumingThePendingToken() {
        User user = userFixture(tenant, TwoFactorMethod.OTP_EMAIL);
        when(redisTokenStore.peek(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        mvc.post()
                .uri("/api/v1/login/2fa-resend")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(202);

        verify(otpService).requestOtp(user);
        // A resend must never burn the one-time pendingToken the user still
        // needs for the real /2fa-verify call.
        verify(redisTokenStore, never()).consume(any(), any());
    }

    @Test
    void resendingForATotpUserIsANoOp() {
        User user = userFixture(tenant, TwoFactorMethod.TOTP);
        when(redisTokenStore.peek(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        mvc.post()
                .uri("/api/v1/login/2fa-resend")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(202);

        verify(otpService, never()).requestOtp(any());
    }

    @Test
    void returns400ForAnUnknownPendingTokenOnResend() {
        when(redisTokenStore.peek(LoginCompletionService.PENDING_2FA_PURPOSE, "bad-token"))
                .thenReturn(Optional.empty());

        mvc.post()
                .uri("/api/v1/login/2fa-resend")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"bad-token"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        verify(userRepository, never()).findById(any());
        verify(otpService, never()).requestOtp(any());
    }

    @Test
    void returns400WhenTheXClientIdDoesNotMatchThePendingTokensClientOnResend() {
        User user = userFixture(tenant, TwoFactorMethod.OTP_EMAIL);
        when(redisTokenStore.peek(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("some-other-client::" + user.getId()));

        mvc.post()
                .uri("/api/v1/login/2fa-resend")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(400)
                .bodyText()
                .contains("invalid_token");

        verify(userRepository, never()).findById(any());
        verify(otpService, never()).requestOtp(any());
    }

    @Test
    void propagatesTheRealResendCooldownAs429() {
        User user = userFixture(tenant, TwoFactorMethod.OTP_SMS);
        when(redisTokenStore.peek(LoginCompletionService.PENDING_2FA_PURPOSE, "pending-token-abc"))
                .thenReturn(Optional.of("acme-web-app::" + user.getId()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        doThrow(new TooManyAttemptsException("A code was already sent recently. Please wait."))
                .when(otpService)
                .requestOtp(user);

        mvc.post()
                .uri("/api/v1/login/2fa-resend")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pendingToken":"pending-token-abc"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(429);
    }
}
