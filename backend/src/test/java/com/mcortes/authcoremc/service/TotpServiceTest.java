package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.LoginRateLimiter;
import com.mcortes.authcoremc.security.SecretEncryptor;
import com.mcortes.authcoremc.security.Totp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@DataRedisTest
@Import({TestcontainersConfiguration.class, SecretEncryptor.class, LoginRateLimiter.class, TotpService.class})
class TotpServiceTest {

    @Autowired
    private TotpService totpService;

    @MockitoBean
    private UserRepository userRepository;

    private static User userFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void enrollingReturnsAUsableSecretAndPersistsItEncrypted() {
        User user = userFixture();

        String secret = totpService.enroll(user);

        assertThat(user.getTotpSecretEncrypted()).isNotNull().doesNotContain(secret);
        verify(userRepository).save(user);
    }

    @Test
    void verifiesTheCurrentCodeAfterEnrollment() {
        User user = userFixture();
        String secret = totpService.enroll(user);

        assertThatCode(() -> totpService.verify(user, Totp.currentCode(secret))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAWrongCode() {
        User user = userFixture();
        totpService.enroll(user);

        assertThatThrownBy(() -> totpService.verify(user, "000000")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsReusingTheSameCodeTwiceEvenThoughItsStillWithinItsWindow() {
        User user = userFixture();
        String secret = totpService.enroll(user);
        String code = Totp.currentCode(secret);
        totpService.verify(user, code);

        assertThatThrownBy(() -> totpService.verify(user, code)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsVerificationBeforeEnrollment() {
        User user = userFixture();

        assertThatThrownBy(() -> totpService.verify(user, "123456")).isInstanceOf(InvalidTokenException.class);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    // Ticket 047: rate-limiting real, reutilizando LoginRateLimiter (mismo mecanismo que OtpService).

    @Test
    void blocksVerificationAfterTooManyWrongAttempts() {
        User user = userFixture();
        totpService.enroll(user);

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            org.assertj.core.api.Assertions.catchThrowable(() -> totpService.verify(user, "000000"));
        }

        assertThatThrownBy(() -> totpService.verify(user, "000000")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void aSuccessfulVerificationResetsTheAttemptCounter() {
        User user = userFixture();
        String secret = totpService.enroll(user);

        // A few wrong guesses, then a real one — none of this should carry
        // over and block the NEXT enrollment/verification cycle for this user.
        org.assertj.core.api.Assertions.catchThrowable(() -> totpService.verify(user, "000000"));
        org.assertj.core.api.Assertions.catchThrowable(() -> totpService.verify(user, "111111"));
        totpService.verify(user, Totp.currentCode(secret));

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS - 1; i++) {
            org.assertj.core.api.Assertions.catchThrowable(() -> totpService.verify(user, "000000"));
        }
        // Still within the limit — the counter was reset by the success above,
        // not accumulated across it.
        assertThatThrownBy(() -> totpService.verify(user, "000000")).isInstanceOf(InvalidTokenException.class);
    }
}
