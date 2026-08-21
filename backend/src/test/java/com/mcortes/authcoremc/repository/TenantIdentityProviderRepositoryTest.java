package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TenantIdentityProviderRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantIdentityProviderRepository providerRepository;

    private Tenant persistedTenant() {
        return tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
    }

    @Test
    void isDisabledByDefaultUntilConfigured() {
        Tenant tenant = persistedTenant();
        TenantIdentityProvider provider =
                providerRepository.save(new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE));

        assertThat(provider.isEnabled()).isFalse();
        assertThat(provider.getClientId()).isNull();
    }

    @Test
    void enablingRequiresBothClientIdAndSecret() {
        TenantIdentityProvider provider =
                new TenantIdentityProvider(persistedTenant(), IdentityProviderType.FACEBOOK);

        assertThatThrownBy(() -> provider.configure("client-id", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.configure(null, "secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuringEnablesTheProviderAndCanBeFoundByTenantAndProvider() {
        Tenant tenant = persistedTenant();
        TenantIdentityProvider provider = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        provider.configure("google-client-id", "encrypted-secret");
        providerRepository.save(provider);

        TenantIdentityProvider found =
                providerRepository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE).orElseThrow();

        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getClientId()).isEqualTo("google-client-id");
        assertThat(found.getClientSecretEncrypted()).isEqualTo("encrypted-secret");
    }

    @Test
    void disablingTurnsEnabledOffWithoutErasingCredentials() {
        Tenant tenant = persistedTenant();
        TenantIdentityProvider provider = new TenantIdentityProvider(tenant, IdentityProviderType.APPLE);
        provider.configure("apple-client-id", "encrypted-secret");

        provider.disable();

        assertThat(provider.isEnabled()).isFalse();
        assertThat(provider.getClientId()).isEqualTo("apple-client-id");
    }
}
