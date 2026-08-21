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
@Import({TestcontainersConfiguration.class, SecretEncryptor.class, TotpService.class})
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
}
