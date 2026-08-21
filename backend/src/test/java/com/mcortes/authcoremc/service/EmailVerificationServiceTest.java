package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.mcortes.authcoremc.notification.VerificationLinkFactory;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.Cooldown;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisTokenStore tokenStore;

    @Mock
    private EmailSender emailSender;

    @Mock
    private Cooldown cooldown;

    @Mock
    private VerificationLinkFactory linkFactory;

    private EmailVerificationService service() {
        return new EmailVerificationService(userRepository, tokenStore, emailSender, cooldown, linkFactory);
    }

    private static User userFixtureWithId(Tenant tenant, String email) {
        User user = new User(tenant, email, null, "Ada", "Lovelace", "argon2-hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    @Test
    void issuesATokenAndSendsTheVerificationEmail() {
        Tenant tenant = tenantFixture();
        User user = userFixtureWithId(tenant, "ada@example.com");
        when(tokenStore.issue(eq("email-verify"), eq(user.getId().toString()), any())).thenReturn("the-token");
        when(linkFactory.build(anyString(), eq("the-token"))).thenReturn("https://auth.example.com/confirm?token=the-token");

        service().requestVerification(user);

        verify(emailSender)
                .send(eq("ada@example.com"), anyString(), org.mockito.ArgumentMatchers.contains("the-token"));
    }

    @Test
    void usesTheTenantsConfigurableTtlForTheToken() {
        Tenant tenant = tenantFixture();
        User user = userFixtureWithId(tenant, "ada@example.com");

        service().requestVerification(user);

        verify(tokenStore).issue("email-verify", user.getId().toString(), Duration.ofSeconds(86_400));
    }

    @Test
    void refusesToSendWhenTheUserHasNoEmail() {
        Tenant tenant = tenantFixture();
        User phoneOnlyUser = new User(tenant, null, "+525512345678", "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(phoneOnlyUser, "id", UUID.randomUUID());

        assertThatThrownBy(() -> service().requestVerification(phoneOnlyUser))
                .isInstanceOf(IllegalArgumentException.class);
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void refusesToResendBeforeTheCooldownElapses() {
        Tenant tenant = tenantFixture();
        User user = userFixtureWithId(tenant, "ada@example.com");
        when(cooldown.isActive("email-verify:" + user.getId())).thenReturn(true);

        assertThatThrownBy(() -> service().requestVerification(user)).isInstanceOf(TooManyAttemptsException.class);
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void confirmingAValidTokenMarksTheUsersEmailVerified() {
        Tenant tenant = tenantFixture();
        User user = userFixtureWithId(tenant, "ada@example.com");
        when(tokenStore.consume("email-verify", "good-token")).thenReturn(Optional.of(user.getId().toString()));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service().confirmVerification("good-token");

        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void rejectsAnExpiredOrUnknownToken() {
        when(tokenStore.consume("email-verify", "bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().confirmVerification("bad-token")).isInstanceOf(InvalidTokenException.class);
        verify(userRepository, never()).save(any());
    }
}
