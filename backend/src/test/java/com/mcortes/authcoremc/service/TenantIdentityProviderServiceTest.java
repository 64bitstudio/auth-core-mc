package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.security.SecretEncryptor;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantIdentityProviderServiceTest {

    @Mock
    private TenantIdentityProviderRepository repository;

    private final SecretEncryptor secretEncryptor = new SecretEncryptor("RrHtxQQxrBRFOMu/D1TuAqDeq/eANE++OIlU9tkFhbY=");

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    private TenantIdentityProviderService service() {
        return new TenantIdentityProviderService(repository, secretEncryptor);
    }

    @Test
    void configuringGoogleEncryptsTheSecretAndEnablesIt() {
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TenantIdentityProvider result =
                service().configure(tenant, IdentityProviderType.GOOGLE, "client-id-123", "super-secret");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getClientId()).isEqualTo("client-id-123");
        assertThat(result.getClientSecretEncrypted()).isNotEqualTo("super-secret");
        assertThat(secretEncryptor.decrypt(result.getClientSecretEncrypted())).isEqualTo("super-secret");
    }

    @Test
    void reusesTheExistingRowWhenReconfiguringAnAlreadyKnownProvider() {
        TenantIdentityProvider existing = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TenantIdentityProvider result =
                service().configure(tenant, IdentityProviderType.GOOGLE, "client-id-123", "super-secret");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void rejectsConfiguringApple() {
        assertThatThrownBy(() -> service().configure(tenant, IdentityProviderType.APPLE, "client-id", "secret"))
                .isInstanceOf(UnsupportedProviderException.class)
                .hasMessageContaining("Apple Developer Program");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsABlankClientIdOrSecret() {
        assertThatThrownBy(() -> service().configure(tenant, IdentityProviderType.GOOGLE, "", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().configure(tenant, IdentityProviderType.GOOGLE, "client-id", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listReturnsWhatTheRepositoryHasForTheTenant() {
        TenantIdentityProvider entry = new TenantIdentityProvider(tenant, IdentityProviderType.FACEBOOK);
        when(repository.findByTenant(tenant)).thenReturn(java.util.List.of(entry));

        assertThat(service().list(tenant)).containsExactly(entry);
    }

    @Test
    void disablingTurnsAnExistingEntryOff() {
        TenantIdentityProvider entry = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        entry.configure("client-id", "encrypted");
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE)).thenReturn(Optional.of(entry));

        service().disable(tenant, IdentityProviderType.GOOGLE);

        assertThat(entry.isEnabled()).isFalse();
        verify(repository).save(entry);
    }

    @Test
    void disablingANeverConfiguredProviderDoesNothing() {
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE)).thenReturn(Optional.empty());

        service().disable(tenant, IdentityProviderType.GOOGLE);

        verify(repository, never()).save(any());
    }
}
