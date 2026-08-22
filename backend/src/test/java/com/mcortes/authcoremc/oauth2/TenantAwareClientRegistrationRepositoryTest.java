package com.mcortes.authcoremc.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.security.TenantSecretEncryptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantAwareClientRegistrationRepositoryTest {

    @Mock
    private IdentityClientRepository identityClientRepository;

    @Mock
    private TenantIdentityProviderRepository tenantIdentityProviderRepository;

    @Mock
    private TenantSecretEncryptor tenantSecretEncryptor;

    private TenantAwareClientRegistrationRepository repository() {
        return new TenantAwareClientRegistrationRepository(
                identityClientRepository, tenantIdentityProviderRepository, tenantSecretEncryptor);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(tenant, "wrappedDataKey", "wrapped-data-key");
        return tenant;
    }

    private static IdentityClient identityClientFixture(Tenant tenant) {
        IdentityClient client = new IdentityClient(
                tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback"));
        ReflectionTestUtils.setField(client, "id", UUID.randomUUID());
        return client;
    }

    private static TenantIdentityProvider enabledProviderFixture(Tenant tenant, IdentityProviderType provider) {
        TenantIdentityProvider tip = new TenantIdentityProvider(tenant, provider);
        tip.configure("tenant-provider-client-id", "encrypted-secret");
        return tip;
    }

    @Test
    void resolvesAnEnabledGoogleProviderIntoAClientRegistration() {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        TenantIdentityProvider tip = enabledProviderFixture(tenant, IdentityProviderType.GOOGLE);
        String registrationId = client.getId() + "::google";

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(tenantIdentityProviderRepository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE))
                .thenReturn(Optional.of(tip));
        when(tenantSecretEncryptor.decrypt("wrapped-data-key", "encrypted-secret")).thenReturn("plaintext-secret");

        ClientRegistration registration = repository().findByRegistrationId(registrationId);

        assertThat(registration).isNotNull();
        assertThat(registration.getRegistrationId()).isEqualTo(registrationId);
        assertThat(registration.getClientId()).isEqualTo("tenant-provider-client-id");
        assertThat(registration.getClientSecret()).isEqualTo("plaintext-secret");
        assertThat(registration.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
    }

    @Test
    void resolvesAnEnabledFacebookProviderIntoAClientRegistration() {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        TenantIdentityProvider tip = enabledProviderFixture(tenant, IdentityProviderType.FACEBOOK);
        String registrationId = client.getId() + "::facebook";

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(tenantIdentityProviderRepository.findByTenantAndProvider(tenant, IdentityProviderType.FACEBOOK))
                .thenReturn(Optional.of(tip));
        when(tenantSecretEncryptor.decrypt("wrapped-data-key", "encrypted-secret")).thenReturn("plaintext-secret");

        ClientRegistration registration = repository().findByRegistrationId(registrationId);

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("tenant-provider-client-id");
        assertThat(registration.getClientSecret()).isEqualTo("plaintext-secret");
    }

    @Test
    void parsingIsCaseInsensitiveOnTheProviderPart() {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        TenantIdentityProvider tip = enabledProviderFixture(tenant, IdentityProviderType.GOOGLE);
        String registrationId = client.getId() + "::GoOgLe";

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(tenantIdentityProviderRepository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE))
                .thenReturn(Optional.of(tip));
        when(tenantSecretEncryptor.decrypt("wrapped-data-key", "encrypted-secret")).thenReturn("plaintext-secret");

        assertThat(repository().findByRegistrationId(registrationId)).isNotNull();
    }

    @Test
    void returnsNullWhenTheIdentityClientUuidDoesNotExist() {
        UUID ghostId = UUID.randomUUID();
        when(identityClientRepository.findById(ghostId)).thenReturn(Optional.empty());

        ClientRegistration registration = repository().findByRegistrationId(ghostId + "::google");

        assertThat(registration).isNull();
    }

    @Test
    void returnsNullWhenTheIdentityClientExistsButTheProviderIsNotEnabled() {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        TenantIdentityProvider disabledTip = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        // Never configured/enabled — isEnabled() stays false, per its constructor default.

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(tenantIdentityProviderRepository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE))
                .thenReturn(Optional.of(disabledTip));

        ClientRegistration registration = repository().findByRegistrationId(client.getId() + "::google");

        assertThat(registration).isNull();
    }

    @Test
    void returnsNullWhenTheProviderWasNeverConfiguredAtAll() {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(tenantIdentityProviderRepository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE))
                .thenReturn(Optional.empty());

        ClientRegistration registration = repository().findByRegistrationId(client.getId() + "::google");

        assertThat(registration).isNull();
    }

    /**
     * The explicit security requirement (ticket 036): an unknown UUID and a
     * known-but-disabled provider must be observably indistinguishable —
     * both null, through the same code path, no differing side effect.
     */
    @Test
    void anUnknownUuidAndADisabledProviderAreIndistinguishable() {
        UUID ghostId = UUID.randomUUID();
        when(identityClientRepository.findById(ghostId)).thenReturn(Optional.empty());
        ClientRegistration forUnknownUuid = repository().findByRegistrationId(ghostId + "::google");

        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        TenantIdentityProvider disabledTip = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(tenantIdentityProviderRepository.findByTenantAndProvider(tenant, IdentityProviderType.GOOGLE))
                .thenReturn(Optional.of(disabledTip));
        ClientRegistration forDisabledProvider = repository().findByRegistrationId(client.getId() + "::google");

        assertThat(forUnknownUuid).isNull();
        assertThat(forDisabledProvider).isNull();
        assertThat(forUnknownUuid).isEqualTo(forDisabledProvider);
    }

    @Test
    void returnsNullForAMalformedRegistrationIdWithNoSeparator() {
        assertThat(repository().findByRegistrationId("not-a-valid-registration-id")).isNull();
    }

    @Test
    void returnsNullWhenTheUuidPartIsNotAValidUuid() {
        assertThat(repository().findByRegistrationId("not-a-uuid::google")).isNull();
    }

    @Test
    void returnsNullForAnUnsupportedProvider() {
        UUID id = UUID.randomUUID();
        assertThat(repository().findByRegistrationId(id + "::twitter")).isNull();
    }

    @Test
    void returnsNullForApple() {
        // Apple has no CommonOAuth2Provider entry and TenantIdentityProviderService
        // already refuses to ever enable it — this proves the lookup fails closed
        // even if a row somehow existed, without ever reaching the repositories.
        UUID id = UUID.randomUUID();
        assertThat(repository().findByRegistrationId(id + "::apple")).isNull();
    }

    @Test
    void returnsNullForANullRegistrationId() {
        assertThat(repository().findByRegistrationId(null)).isNull();
    }
}
