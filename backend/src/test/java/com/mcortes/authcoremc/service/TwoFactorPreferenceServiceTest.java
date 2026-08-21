package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TotpNotEnrolledException;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TwoFactorPreferenceServiceTest {

    @Mock
    private UserRepository userRepository;

    private static User userFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        return new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
    }

    @Test
    void activatesOtpEmailWithoutAnyPriorEnrollment() {
        User user = userFixture();

        new TwoFactorPreferenceService(userRepository).activate(user, TwoFactorMethod.OTP_EMAIL);

        assertThat(user.getTwoFactorMethod()).isEqualTo(TwoFactorMethod.OTP_EMAIL);
        verify(userRepository).save(user);
    }

    @Test
    void refusesToActivateTotpBeforeItsEnrolled() {
        User user = userFixture();

        assertThatThrownBy(() -> new TwoFactorPreferenceService(userRepository).activate(user, TwoFactorMethod.TOTP))
                .isInstanceOf(TotpNotEnrolledException.class);
    }

    @Test
    void activatesTotpOnceEnrolled() {
        User user = userFixture();
        user.enrollTotpSecret("encrypted-secret");

        new TwoFactorPreferenceService(userRepository).activate(user, TwoFactorMethod.TOTP);

        assertThat(user.getTwoFactorMethod()).isEqualTo(TwoFactorMethod.TOTP);
    }
}
