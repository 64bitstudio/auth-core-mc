package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Adapts our own {@link IdentityClient} table to Spring Authorization
 * Server's {@link RegisteredClient} model, instead of Spring's own
 * {@code oauth2_registered_client} schema — see docs/BASE_DE_DATOS.md
 * ("Nota de nombres") for why {@code identity_client} exists as a
 * deliberately separate table.
 *
 * <p>Token lifetimes come from the client's {@link Tenant}
 * (docs/ARQUITECTURA.md decision: "los tiempos de expiración son de
 * configuración, no de código") — every lookup rebuilds the
 * {@link RegisteredClient} fresh, so a TTL change in the {@code tenant}
 * table takes effect on the next request, no redeploy needed.
 *
 * <p>A first-party client (see ticket 002/007) is public (PKCE, no client
 * secret, {@link ClientAuthenticationMethod#NONE}) and skips the consent
 * screen; a third-party client is confidential ({@code client_secret_hash}
 * required) and always shows consent. Spring Authorization Server enforces
 * PKCE for public clients by default — {@link ClientSettings} doesn't need
 * to force it separately here.
 */
@Component
public class TenantAwareRegisteredClientRepository implements RegisteredClientRepository {

    private final IdentityClientRepository identityClientRepository;

    public TenantAwareRegisteredClientRepository(IdentityClientRepository identityClientRepository) {
        this.identityClientRepository = identityClientRepository;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException(
                "Registered clients are managed through IdentityClient/IdentityClientRepository, not this API");
    }

    @Override
    public RegisteredClient findById(String id) {
        return identityClientRepository
                .findById(UUID.fromString(id))
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return identityClientRepository
                .findByClientId(clientId)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    private RegisteredClient toRegisteredClient(IdentityClient entity) {
        Tenant tenant = entity.getTenant();

        RegisteredClient.Builder builder = RegisteredClient.withId(entity.getId().toString())
                .clientId(entity.getClientId())
                .clientAuthenticationMethod(
                        entity.isFirstParty() ? ClientAuthenticationMethod.NONE : ClientAuthenticationMethod.CLIENT_SECRET_BASIC);

        // Ticket 048: un cliente machine-to-machine (mail-core-mc y
        // futuros servicios app-a-app) usa client_credentials, no
        // Authorization Code — no hay usuario humano completando un
        // login. Sus scopes son los que el cliente pidió al registrarse
        // (ej. "mail:send"), no el "openid"+"profile" de identidad de
        // usuario, que no aplica cuando no hay usuario.
        if (entity.isMachineClient()) {
            builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
        } else {
            builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }
        entity.getScopes().forEach(builder::scope);

        builder.clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(!entity.isFirstParty())
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(tenant.getAccessTokenTtlSeconds()))
                        .refreshTokenTimeToLive(Duration.ofSeconds(tenant.getRefreshTokenTtlSeconds()))
                        .reuseRefreshTokens(false)
                        .build());

        if (entity.getClientSecretHash() != null) {
            builder.clientSecret(entity.getClientSecretHash());
        }
        if (entity.getRedirectUris() != null) {
            entity.getRedirectUris().forEach(builder::redirectUri);
        }
        return builder.build();
    }
}
