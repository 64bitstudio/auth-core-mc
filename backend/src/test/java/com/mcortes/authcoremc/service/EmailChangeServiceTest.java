package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.notification.EmailSender;
import com.mcortes.authcoremc.notification.VerificationLinkFactory;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailChangeServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RedisTokenStore tokenStore;

    @Mock
    private EmailSender emailSender;

    @Mock
    private VerificationLinkFactory linkFactory;

    private EmailChangeService service() {
        return new EmailChangeService(userRepository, tokenStore, emailSender, linkFactory);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private static User userFixture(Tenant tenant) {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static IdentityClient clientFixture(Tenant tenant) {
        return new IdentityClient(tenant, "acme-web-app", null, true, List.of());
    }

    @Test
    void sendsTheConfirmationToTheNewAddressNotTheOldOne() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant);
        IdentityClient client = clientFixture(tenant);
        when(userRepository.findByTenantAndEmail(tenant, "ada.new@example.com")).thenReturn(Optional.empty());
        when(tokenStore.issue(eq("email-change"), anyString(), any())).thenReturn("the-token");
        when(linkFactory.build(eq(client), anyString(), eq("the-token")))
                .thenReturn("https://auth.example.com/confirm?token=the-token");

        service().requestChange(user, "ada.new@example.com", client);

        verify(emailSender).send(eq("ada.new@example.com"), anyString(), contains("the-token"));
    }

    @Test
    void rejectsAMalformedNewEmail() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant);

        assertThatThrownBy(() -> service().requestChange(user, "not-an-email", clientFixture(tenant)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void rejectsANewEmailAlreadyUsedInTheSameTenant() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant);
        when(userRepository.findByTenantAndEmail(tenant, "taken@example.com"))
                .thenReturn(Optional.of(userFixture(tenant)));

        assertThatThrownBy(() -> service().requestChange(user, "taken@example.com", clientFixture(tenant)))
                .isInstanceOf(DuplicateIdentifierException.class);
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void confirmingAValidTokenAppliesTheNewEmailAndMarksItVerified() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant);
        when(tokenStore.consume("email-change", "good-token"))
                .thenReturn(Optional.of(user.getId() + "::ada.new@example.com"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findByTenantAndEmail(tenant, "ada.new@example.com")).thenReturn(Optional.empty());

        service().confirmChange("good-token");

        assertThat(user.getEmail()).isEqualTo("ada.new@example.com");
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void rejectsConfirmationIfSomeoneElseClaimedTheEmailWhileTheLinkWasOutstanding() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant);
        User someoneElse = userFixture(tenant);
        when(tokenStore.consume("email-change", "good-token"))
                .thenReturn(Optional.of(user.getId() + "::ada.new@example.com"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findByTenantAndEmail(tenant, "ada.new@example.com")).thenReturn(Optional.of(someoneElse));

        assertThatThrownBy(() -> service().confirmChange("good-token")).isInstanceOf(DuplicateIdentifierException.class);
    }

    @Test
    void rejectsAnExpiredOrUnknownToken() {
        when(tokenStore.consume("email-change", "bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().confirmChange("bad-token")).isInstanceOf(InvalidTokenException.class);
    }
}
