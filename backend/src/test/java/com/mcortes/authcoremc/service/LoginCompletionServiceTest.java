package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoginCompletionServiceTest {

    @Mock
    private DirectTokenService directTokenService;

    @Mock
    private RedisTokenStore redisTokenStore;

    @Mock
    private OtpService otpService;

    private static final Tenant TENANT = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    private final IdentityClient client =
            new IdentityClient(TENANT, "acme-web-app", null, true, List.of("https://acme.example.com/callback"));

    private static User userFixture(TwoFactorMethod method) {
        User user = new User(TENANT, "ada@example.com", null, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        if (method != TwoFactorMethod.NONE) {
            if (method == TwoFactorMethod.TOTP) {
                user.enrollTotpSecret("encrypted-secret");
            }
            user.activateTwoFactorMethod(method);
        }
        return user;
    }

    private LoginCompletionService service() {
        return new LoginCompletionService(directTokenService, redisTokenStore, otpService);
    }

    @Test
    void mintsTokensRightAwayWhenTheUserHasNoTwoFactorMethod() {
        User user = userFixture(TwoFactorMethod.NONE);
        TokenPair tokens = new TokenPair("jwt-access-token", "opaque-refresh-token", "Bearer", 900);
        when(directTokenService.issueTokens(client, user)).thenReturn(tokens);

        LoginCompletionResult result = service().complete(client, user);

        assertThat(result.twoFactorRequired()).isFalse();
        assertThat(result.user()).isEqualTo(user);
        assertThat(result.tokens()).isEqualTo(tokens);
        assertThat(result.pendingToken()).isNull();
        verify(redisTokenStore, never()).issue(anyString(), anyString(), any());
        verify(otpService, never()).requestOtp(any());
    }

    @Test
    void issuesAPendingTokenInsteadOfMintingForTotp() {
        User user = userFixture(TwoFactorMethod.TOTP);
        when(redisTokenStore.issue(eq(LoginCompletionService.PENDING_2FA_PURPOSE), anyString(), eq(Duration.ofMinutes(5))))
                .thenReturn("pending-token-abc");

        LoginCompletionResult result = service().complete(client, user);

        assertThat(result.twoFactorRequired()).isTrue();
        assertThat(result.pendingToken()).isEqualTo("pending-token-abc");
        assertThat(result.method()).isEqualTo(TwoFactorMethod.TOTP);
        assertThat(result.user()).isNull();
        assertThat(result.tokens()).isNull();
        verify(directTokenService, never()).issueTokens(any(), any());
        verify(otpService, never()).requestOtp(any()); // TOTP never needs a code sent
    }

    @Test
    void packsClientIdAndUserIdIntoThePendingTokenValue() {
        User user = userFixture(TwoFactorMethod.TOTP);

        service().complete(client, user);

        verify(redisTokenStore)
                .issue(
                        LoginCompletionService.PENDING_2FA_PURPOSE,
                        "acme-web-app::" + user.getId(),
                        Duration.ofMinutes(5));
    }

    @Test
    void aPendingTokenValueParsesBackIntoClientIdAndUserId() {
        UUID userId = UUID.randomUUID();

        LoginCompletionService.PendingLogin pending =
                LoginCompletionService.parsePendingLogin("acme-web-app::" + userId);

        assertThat(pending.clientId()).isEqualTo("acme-web-app");
        assertThat(pending.userId()).isEqualTo(userId);
    }

    @Test
    void sendsAnOtpCodeAndIssuesAPendingTokenForOtpEmail() {
        User user = userFixture(TwoFactorMethod.OTP_EMAIL);
        when(redisTokenStore.issue(eq(LoginCompletionService.PENDING_2FA_PURPOSE), anyString(), any()))
                .thenReturn("pending-token-abc");

        LoginCompletionResult result = service().complete(client, user);

        assertThat(result.twoFactorRequired()).isTrue();
        assertThat(result.method()).isEqualTo(TwoFactorMethod.OTP_EMAIL);
        verify(otpService, times(1)).requestOtp(user);
    }

    @Test
    void sendsAnOtpCodeForOtpSmsToo() {
        User user = userFixture(TwoFactorMethod.OTP_SMS);

        service().complete(client, user);

        verify(otpService, times(1)).requestOtp(user);
    }

    /**
     * A cooldown collision on the OTP send isn't surfaced as a login
     * failure — the previously-sent code is still valid, see the class
     * Javadoc's explicit rationale.
     */
    @Test
    void stillReturnsAPendingTokenWhenTheOtpResendCooldownIsActive() {
        User user = userFixture(TwoFactorMethod.OTP_EMAIL);
        doThrow(new TooManyAttemptsException("cooldown active")).when(otpService).requestOtp(user);
        when(redisTokenStore.issue(eq(LoginCompletionService.PENDING_2FA_PURPOSE), anyString(), any()))
                .thenReturn("pending-token-abc");

        LoginCompletionResult result = service().complete(client, user);

        assertThat(result.twoFactorRequired()).isTrue();
        assertThat(result.pendingToken()).isEqualTo("pending-token-abc");
    }
}
