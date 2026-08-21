package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.SmsSender;
import com.mcortes.authcoremc.security.Cooldown;
import com.mcortes.authcoremc.security.LoginRateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@DataRedisTest
@Import({TestcontainersConfiguration.class, LoginRateLimiter.class, Cooldown.class, OtpService.class})
class OtpServiceTest {

    @Autowired
    private OtpService otpService;

    @MockitoBean
    private EmailSender emailSender;

    @MockitoBean
    private SmsSender smsSender;

    private static User userFixture(String email, String phone) {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        User user = new User(tenant, email, phone, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void sendsTheCodeByEmailWhenAvailable() {
        User user = userFixture("ada@example.com", null);

        otpService.requestOtp(user);

        verify(emailSender).send(eq("ada@example.com"), any(), any());
        verify(smsSender, never()).send(any(), any());
    }

    @Test
    void sendsTheCodeBySmsForAPhoneOnlyAccount() {
        User user = userFixture(null, "+525512345678");

        otpService.requestOtp(user);

        verify(smsSender).send(eq("+525512345678"), any());
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void refusesToResendBeforeTheCooldownElapses() {
        User user = userFixture("ada@example.com", null);
        otpService.requestOtp(user);

        assertThatThrownBy(() -> otpService.requestOtp(user)).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void verifyingTheCorrectCodeSucceedsAndConsumesIt() {
        User user = userFixture("ada@example.com", null);
        String sentCode = captureSentCode(user);

        assertThatCode(() -> otpService.verifyOtp(user, sentCode)).doesNotThrowAnyException();
        // one-time use: the same code can't be verified again
        assertThatThrownBy(() -> otpService.verifyOtp(user, sentCode)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAWrongCode() {
        User user = userFixture("ada@example.com", null);
        otpService.requestOtp(user);

        assertThatThrownBy(() -> otpService.verifyOtp(user, "000000")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void blocksVerificationAfterTooManyWrongAttempts() {
        User user = userFixture("ada@example.com", null);
        otpService.requestOtp(user);

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            org.assertj.core.api.Assertions.catchThrowable(() -> otpService.verifyOtp(user, "000000"));
        }

        assertThatThrownBy(() -> otpService.verifyOtp(user, "000000")).isInstanceOf(TooManyAttemptsException.class);
    }

    /** Captures the code passed to whichever sender fires, by requesting OTP and inspecting the mock invocation. */
    private String captureSentCode(User user) {
        otpService.requestOtp(user);
        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(any(), any(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        return body.replaceAll("\\D", "");
    }
}
