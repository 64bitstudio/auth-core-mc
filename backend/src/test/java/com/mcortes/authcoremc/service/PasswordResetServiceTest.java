package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.SmsSender;
import com.mcortes.authcoremc.notification.VerificationLinkFactory;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.Cooldown;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RedisTokenStore tokenStore;

    @Mock
    private EmailSender emailSender;

    @Mock
    private SmsSender smsSender;

    @Mock
    private Cooldown cooldown;

    @Mock
    private VerificationLinkFactory linkFactory;

    private PasswordResetService service() {
        return new PasswordResetService(
                userRepository, passwordEncoder, tokenStore, emailSender, smsSender, cooldown, linkFactory);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    @Test
    void doesNothingObservableForAnUnknownIdentifier() {
        Tenant tenant = tenantFixture();
        when(userRepository.findByTenantAndEmail(tenant, "ghost@example.com")).thenReturn(Optional.empty());

        assertThatCode(() -> service().requestReset(tenant, "ghost@example.com")).doesNotThrowAnyException();
        verify(emailSender, never()).send(any(), any(), any());
        verify(smsSender, never()).send(any(), any());
    }

    @Test
    void sendsAnEmailWhenTheUserHasOneEvenIfAPhoneAlsoExists() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", "+525512345678", "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(user));
        when(tokenStore.issue(eq("password-reset"), anyString(), any())).thenReturn("the-token");
        when(linkFactory.build(anyString(), eq("the-token"))).thenReturn("https://auth.example.com/reset?token=the-token");

        service().requestReset(tenant, "ada@example.com");

        verify(emailSender).send(eq("ada@example.com"), anyString(), org.mockito.ArgumentMatchers.contains("the-token"));
        verify(smsSender, never()).send(any(), any());
    }

    @Test
    void sendsAnSmsForAPhoneOnlyAccount() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, null, "+525512345678", "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByTenantAndPhone(tenant, "+525512345678")).thenReturn(Optional.of(user));
        when(tokenStore.issue(eq("password-reset"), anyString(), any())).thenReturn("the-token");
        when(linkFactory.build(anyString(), eq("the-token"))).thenReturn("https://auth.example.com/reset?token=the-token");

        service().requestReset(tenant, "+525512345678");

        verify(smsSender).send(eq("+525512345678"), org.mockito.ArgumentMatchers.contains("the-token"));
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void doesNothingWhenTheCooldownIsActiveRegardlessOfWhetherTheUserExists() {
        Tenant tenant = tenantFixture();
        when(cooldown.isActive("password-reset:" + tenant.getId() + ":ada@example.com")).thenReturn(true);

        service().requestReset(tenant, "ada@example.com");

        verify(userRepository, never()).findByTenantAndEmail(any(), any());
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void confirmingAValidTokenUpdatesThePasswordHash() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "old-hash");
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(tokenStore.consume("password-reset", "good-token")).thenReturn(Optional.of(userId.toString()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");

        service().confirmReset("good-token", "newpass123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void rejectsAWeakNewPassword() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "old-hash");
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(tokenStore.consume("password-reset", "good-token")).thenReturn(Optional.of(userId.toString()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().confirmReset("good-token", "weak")).isInstanceOf(WeakPasswordException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsAnExpiredOrUnknownToken() {
        when(tokenStore.consume("password-reset", "bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().confirmReset("bad-token", "newpass123"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
