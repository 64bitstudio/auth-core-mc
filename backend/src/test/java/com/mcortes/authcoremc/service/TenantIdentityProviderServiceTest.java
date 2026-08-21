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
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.security.TenantSecretEncryptor;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code secretEncryptor} is a mock stubbed with a trivial reversible
 * transform (see {@code stubEncryption()}) — real AES/Vault round-trip
 * correctness is proven separately by {@code TenantSecretEncryptorTest}
 * (ticket 017); this test only proves *this service's* behavior: it calls
 * ensureWrappedDataKey, never stores the raw secret, saves the tenant when
 * (and only when) a data-key was newly generated.
 */
@ExtendWith(MockitoExtension.class)
class TenantIdentityProviderServiceTest {

    @Mock
    private TenantIdentityProviderRepository repository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantSecretEncryptor secretEncryptor;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

    private TenantIdentityProviderService service() {
        return new TenantIdentityProviderService(repository, tenantRepository, secretEncryptor);
    }

    /** Trivial reversible stub — real crypto is TenantSecretEncryptorTest's job, not this test's. */
    private void stubEncryption() {
        when(secretEncryptor.ensureWrappedDataKey(tenant)).thenAnswer(invocation -> {
            if (tenant.getWrappedDataKey() == null) {
                tenant.setWrappedDataKey("wrapped-data-key");
            }
            return tenant.getWrappedDataKey();
        });
        when(secretEncryptor.encrypt(any(), any()))
                .thenAnswer(invocation -> "encrypted:" + invocation.getArgument(1, String.class));
    }

    @Test
    void configuringGoogleEncryptsTheSecretAndEnablesIt() {
        stubEncryption();
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TenantIdentityProvider result =
                service().configure(tenant, IdentityProviderType.GOOGLE, "client-id-123", "super-secret");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getClientId()).isEqualTo("client-id-123");
        assertThat(result.getClientSecretEncrypted()).isNotEqualTo("super-secret");
        assertThat(result.getClientSecretEncrypted()).isEqualTo("encrypted:super-secret");
    }

    @Test
    void configuringATenantsFirstProviderGeneratesAndSavesItsDataKey() {
        stubEncryption();
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().configure(tenant, IdentityProviderType.GOOGLE, "client-id-123", "super-secret");

        assertThat(tenant.getWrappedDataKey()).isEqualTo("wrapped-data-key");
        verify(tenantRepository).save(tenant);
    }

    @Test
    void configuringASecondProviderForATenantThatAlreadyHasADataKeyDoesNotSaveTheTenantAgain() {
        tenant.setWrappedDataKey("already-wrapped");
        stubEncryption();
        when(repository.findByTenantAndProvider(tenant, IdentityProviderType.FACEBOOK)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().configure(tenant, IdentityProviderType.FACEBOOK, "client-id-456", "another-secret");

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void reusesTheExistingRowWhenReconfiguringAnAlreadyKnownProvider() {
        stubEncryption();
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
