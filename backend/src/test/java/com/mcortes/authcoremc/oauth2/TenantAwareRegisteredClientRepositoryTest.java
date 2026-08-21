package com.mcortes.authcoremc.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantAwareRegisteredClientRepositoryTest {

    @Mock
    private IdentityClientRepository identityClientRepository;

    private TenantAwareRegisteredClientRepository repository() {
        return new TenantAwareRegisteredClientRepository(identityClientRepository);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private static IdentityClient clientFixture(Tenant tenant, boolean firstParty, String secretHash) {
        IdentityClient client = new IdentityClient(
                tenant, "acme-web-app", secretHash, firstParty, List.of("https://acme.example.com/callback"));
        ReflectionTestUtils.setField(client, "id", UUID.randomUUID());
        return client;
    }

    @Test
    void aFirstPartyClientIsPublicWithNoSecretRequired() {
        Tenant tenant = tenantFixture();
        IdentityClient client = clientFixture(tenant, true, null);
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(client));

        RegisteredClient registeredClient = repository().findByClientId("acme-web-app");

        assertThat(registeredClient.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(registeredClient.getClientSecret()).isNull();
        assertThat(registeredClient.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    }

    @Test
    void aThirdPartyClientIsConfidentialAndRequiresConsent() {
        Tenant tenant = tenantFixture();
        IdentityClient client = clientFixture(tenant, false, "hashed-secret");
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(client));

        RegisteredClient registeredClient = repository().findByClientId("acme-web-app");

        assertThat(registeredClient.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registeredClient.getClientSecret()).isEqualTo("hashed-secret");
        assertThat(registeredClient.getClientSettings().isRequireAuthorizationConsent()).isTrue();
    }

    @Test
    void tokenTtlsComeFromTheClientsTenant() {
        Tenant tenant = tenantFixture();
        IdentityClient client = clientFixture(tenant, true, null);
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(client));

        RegisteredClient registeredClient = repository().findByClientId("acme-web-app");

        assertThat(registeredClient.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofSeconds(900));
        assertThat(registeredClient.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(Duration.ofSeconds(2_592_000));
    }

    @Test
    void alwaysSupportsAuthorizationCodeAndRefreshTokenGrants() {
        Tenant tenant = tenantFixture();
        IdentityClient client = clientFixture(tenant, true, null);
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(client));

        RegisteredClient registeredClient = repository().findByClientId("acme-web-app");

        assertThat(registeredClient.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
    }

    @Test
    void redirectUrisComeFromTheClient() {
        Tenant tenant = tenantFixture();
        IdentityClient client = clientFixture(tenant, true, null);
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(client));

        RegisteredClient registeredClient = repository().findByClientId("acme-web-app");

        assertThat(registeredClient.getRedirectUris()).containsExactly("https://acme.example.com/callback");
    }

    @Test
    void findByIdUsesOurOwnUuidAsSpringsInternalId() {
        Tenant tenant = tenantFixture();
        IdentityClient client = clientFixture(tenant, true, null);
        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));

        RegisteredClient registeredClient = repository().findById(client.getId().toString());

        assertThat(registeredClient.getId()).isEqualTo(client.getId().toString());
        assertThat(registeredClient.getClientId()).isEqualTo("acme-web-app");
    }

    @Test
    void returnsNullForAnUnknownClientId() {
        when(identityClientRepository.findByClientId("ghost")).thenReturn(Optional.empty());

        assertThat(repository().findByClientId("ghost")).isNull();
    }

    @Test
    void saveIsNotSupported() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository().save(RegisteredClient.withId("x")
                        .clientId("x")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
