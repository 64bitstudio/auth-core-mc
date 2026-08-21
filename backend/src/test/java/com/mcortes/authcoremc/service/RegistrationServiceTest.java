package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    @Test
    void hashesThePasswordBeforePersistingTheUser() {
        when(passwordEncoder.encode("abcd1234")).thenReturn("argon2-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = new RegistrationService(userRepository, passwordEncoder)
                .register(tenant, "ada@example.com", null, "Ada", "Lovelace", "abcd1234");

        assertThat(user.getPasswordHash()).isEqualTo("argon2-hash");
        verify(passwordEncoder).encode("abcd1234");
    }

    @Test
    void rejectsAWeakPasswordBeforeEverTouchingTheRepository() {
        RegistrationService service = new RegistrationService(userRepository, passwordEncoder);

        assertThatThrownBy(() -> service.register(tenant, "ada@example.com", null, "Ada", "Lovelace", "weak"))
                .isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void rejectsAMalformedEmail() {
        RegistrationService service = new RegistrationService(userRepository, passwordEncoder);

        assertThatThrownBy(() -> service.register(tenant, "not-an-email", null, "Ada", "Lovelace", "abcd1234"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsAMalformedPhone() {
        RegistrationService service = new RegistrationService(userRepository, passwordEncoder);

        assertThatThrownBy(() -> service.register(tenant, null, "not-a-phone", "Ada", "Lovelace", "abcd1234"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void treatsABlankEmailAsAbsentSoOnlyPhoneIsRequired() {
        when(passwordEncoder.encode(any())).thenReturn("argon2-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = new RegistrationService(userRepository, passwordEncoder)
                .register(tenant, "   ", "+525512345678", "Ada", "Lovelace", "abcd1234");

        assertThat(user.getEmail()).isNull();
        assertThat(user.getPhone()).isEqualTo("+525512345678");
    }

    @Test
    void translatesADatabaseUniqueConstraintViolationIntoADomainException() {
        when(passwordEncoder.encode(any())).thenReturn("argon2-hash");
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        RegistrationService service = new RegistrationService(userRepository, passwordEncoder);

        assertThatThrownBy(() -> service.register(tenant, "ada@example.com", null, "Ada", "Lovelace", "abcd1234"))
                .isInstanceOf(DuplicateIdentifierException.class);
    }
}
