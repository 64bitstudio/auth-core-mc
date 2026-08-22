package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.ExternalIdentityRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class SocialLoginUserResolverTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ExternalIdentityRepository externalIdentityRepository;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    private SocialLoginUserResolver resolver() {
        return new SocialLoginUserResolver(userRepository, externalIdentityRepository);
    }

    @Test
    void createsANewUserWithEmailVerifiedWhenTheProviderReportsItVerified() {
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SocialProfile profile = new SocialProfile("ada@example.com", true, "google-sub-1", "Ada", "Lovelace");
        User user = resolver().resolve(tenant, IdentityProviderType.GOOGLE, profile);

        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getNombre()).isEqualTo("Ada");
        assertThat(user.getApellidos()).isEqualTo("Lovelace");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getPasswordHash()).isNull();

        ArgumentCaptor<ExternalIdentity> linkCaptor = ArgumentCaptor.forClass(ExternalIdentity.class);
        verify(externalIdentityRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getProviderUserId()).isEqualTo("google-sub-1");
        assertThat(linkCaptor.getValue().getProvider()).isEqualTo(IdentityProviderType.GOOGLE);
    }

    @Test
    void createsANewUserWithEmailUnverifiedWhenTheProviderDoesNotReportItVerified() {
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-2"))
                .thenReturn(Optional.empty());
        when(userRepository.findByTenantAndEmail(tenant, "unverified@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SocialProfile profile = new SocialProfile("unverified@example.com", false, "google-sub-2", "Grace", "Hopper");
        User user = resolver().resolve(tenant, IdentityProviderType.GOOGLE, profile);

        // Not blocked — HU-1's third criterion: the account is created anyway,
        // subject to the normal (ticket 003) verification flow.
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void fallsBackToTheEmailLocalPartAndAnEmptySurnameWhenTheProviderGivesNoName() {
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.FACEBOOK, "fb-3"))
                .thenReturn(Optional.empty());
        when(userRepository.findByTenantAndEmail(tenant, "noname@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SocialProfile profile = new SocialProfile("noname@example.com", true, "fb-3", null, null);
        User user = resolver().resolve(tenant, IdentityProviderType.FACEBOOK, profile);

        assertThat(user.getNombre()).isEqualTo("noname");
        assertThat(user.getApellidos()).isEmpty();
    }

    @Test
    void autoLinksAnExistingUserWhenTheProviderReportsTheEmailVerified() {
        User existing = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "some-hash");
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-4"))
                .thenReturn(Optional.empty());
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(existing));

        SocialProfile profile = new SocialProfile("ada@example.com", true, "google-sub-4", "Ada", "Lovelace");
        User user = resolver().resolve(tenant, IdentityProviderType.GOOGLE, profile);

        assertThat(user).isSameAs(existing);
        verify(userRepository, never()).save(any());
        verify(externalIdentityRepository).save(any(ExternalIdentity.class));
    }

    @Test
    void blocksLinkingWhenAnExistingUserMatchesButTheProviderDoesNotReportTheEmailVerified() {
        User existing = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "some-hash");
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-5"))
                .thenReturn(Optional.empty());
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(existing));

        SocialProfile profile = new SocialProfile("ada@example.com", false, "google-sub-5", "Ada", "Lovelace");

        assertThatThrownBy(() -> resolver().resolve(tenant, IdentityProviderType.GOOGLE, profile))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(SocialLoginBlockedException.class))
                .extracting(SocialLoginBlockedException::getCode)
                .isEqualTo("social_login_email_conflict");
        verify(externalIdentityRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aReturningSocialUserIsResolvedDirectlyWithoutTouchingTheEmailLookup() {
        User existing = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null);
        ExternalIdentity link = new ExternalIdentity(tenant, existing, IdentityProviderType.GOOGLE, "google-sub-6");
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-6"))
                .thenReturn(Optional.of(link));

        // Deliberately implausible email/verified values — proves the fast
        // path never consults them.
        SocialProfile profile = new SocialProfile("ignored@example.com", false, "google-sub-6", null, null);
        User user = resolver().resolve(tenant, IdentityProviderType.GOOGLE, profile);

        assertThat(user).isSameAs(existing);
        verifyNoInteractions(userRepository);
    }

    @Test
    void aConcurrentDuplicateLinkAttemptDoesNotFailTheLogin() {
        User existing = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "some-hash");
        when(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-7"))
                .thenReturn(Optional.empty());
        when(userRepository.findByTenantAndEmail(tenant, "ada@example.com")).thenReturn(Optional.of(existing));
        when(externalIdentityRepository.save(any(ExternalIdentity.class)))
                .thenThrow(new DataIntegrityViolationException("external_identity_user_provider_unique"));

        SocialProfile profile = new SocialProfile("ada@example.com", true, "google-sub-7", "Ada", "Lovelace");
        User user = resolver().resolve(tenant, IdentityProviderType.GOOGLE, profile);

        assertThat(user).isSameAs(existing);
    }
}
