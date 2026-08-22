package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SetPasswordServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SetPasswordService service() {
        return new SetPasswordService(userRepository, passwordEncoder);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    @Test
    void setsThePasswordOnASocialOnlyAccountWithNoPasswordYet() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null);
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("argon2-hash");
        when(userRepository.save(user)).thenReturn(user);

        User result = service().setPassword(userId, "newpass123");

        assertThat(result.getPasswordHash()).isEqualTo("argon2-hash");
        verify(userRepository).save(user);
    }

    @Test
    void rejectsSettingAPasswordWhenTheAccountAlreadyHasOne() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "existing-hash");
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        SetPasswordService service = service();
        assertThatThrownBy(() -> service.setPassword(userId, "newpass123"))
                .isInstanceOf(PasswordAlreadySetException.class);

        assertThat(user.getPasswordHash()).isEqualTo("existing-hash");
        verify(userRepository, never()).save(user);
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAWeakPasswordWithoutTouchingTheExistingNullHash() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null);
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        SetPasswordService service = service();
        assertThatThrownBy(() -> service.setPassword(userId, "weak")).isInstanceOf(WeakPasswordException.class);

        assertThat(user.getPasswordHash()).isNull();
        verify(userRepository, never()).save(user);
    }

    @Test
    void rejectsAnUnknownUserId() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        SetPasswordService service = service();
        assertThatThrownBy(() -> service.setPassword(userId, "newpass123"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
