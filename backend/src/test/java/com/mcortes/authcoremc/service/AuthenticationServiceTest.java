package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.LoginRateLimiter;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginRateLimiter rateLimiter;

    // Tenant.id is only ever assigned by JPA on persist (@GeneratedValue) —
    // AuthenticationService keys the rate limiter by it, so this fixture
    // needs one even though nothing here touches a real database.
    private final Tenant tenant = tenantFixtureWithId();

    private static Tenant tenantFixtureWithId() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private AuthenticationService service() {
        return new AuthenticationService(userRepository, passwordEncoder, rateLimiter);
    }

    @Test
    void authenticatesAValidEmailAndPassword() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "argon2-hash");
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("abcd1234", "argon2-hash")).thenReturn(true);

        User authenticated = service().authenticate(tenant, "ada@example.com", "abcd1234");

        assertThat(authenticated).isEqualTo(user);
        verify(rateLimiter).recordSuccess(eq(tenant.getId().toString()), eq("ada@example.com"));
    }

    @Test
    void authenticatesByPhoneWhenTheIdentifierIsNotAnEmail() {
        User user = new User(tenant, null, "+525512345678", "Ada", "Lovelace", "argon2-hash");
        when(userRepository.findByTenantAndPhone(tenant, "+525512345678")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("abcd1234", "argon2-hash")).thenReturn(true);

        User authenticated = service().authenticate(tenant, "+525512345678", "abcd1234");

        assertThat(authenticated).isEqualTo(user);
    }

    @Test
    void rejectsAnUnknownIdentifierWithoutRevealingWhichPartWasWrong() {
        when(userRepository.findByTenantAndEmail(tenant, "ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().authenticate(tenant, "ghost@example.com", "abcd1234"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(rateLimiter).recordFailure(tenant.getId().toString(), "ghost@example.com");
    }

    @Test
    void rejectsAWrongPassword() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "argon2-hash");
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "argon2-hash")).thenReturn(false);

        assertThatThrownBy(() -> service().authenticate(tenant, "ada@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsASocialOnlyUserThatHasNoPasswordAtAll() {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null);
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().authenticate(tenant, "ada@example.com", "abcd1234"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refusesToEvenCheckThePasswordWhenTheRateLimiterBlocksTheAttempt() {
        doThrow(new TooManyAttemptsException("blocked")).when(rateLimiter).checkAllowed(any(), any());

        assertThatThrownBy(() -> service().authenticate(tenant, "ada@example.com", "abcd1234"))
                .isInstanceOf(TooManyAttemptsException.class);
        verify(userRepository, never()).findByTenantAndEmail(any(), any());
    }
}
