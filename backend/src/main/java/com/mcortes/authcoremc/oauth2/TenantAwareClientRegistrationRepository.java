package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.security.TenantSecretEncryptor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Adapts {@link TenantIdentityProvider} (a tenant's own Google/Facebook
 * credentials, ticket 006/017) to Spring Security's {@link ClientRegistration}
 * model for the client-side social-login flow (ticket 036,
 * docs/definiciones/login-social-real.md, Diseño técnico decisiones 1/2/5) —
 * the {@code ClientRegistrationRepository} counterpart to
 * {@link TenantAwareRegisteredClientRepository}, same spirit: reconstructed
 * fresh on every lookup, no cache, so a config change (rotated secret,
 * disabled provider) takes effect on the very next request. The two-round-trip
 * cost to Vault per login attempt (redirect + callback, see the sequence
 * diagram "Login social exitoso" in the definition doc) is a deliberate,
 * accepted tradeoff — login isn't a hot path.
 *
 * <p>{@code registrationId} encodes tenant + provider:
 * {@code "{identityClient.id}::{provider}"} (lowercase provider, e.g.
 * {@code "google"}/{@code "facebook"}) — this is how the tenant survives the
 * redirect to Google/Facebook and back without server-side session state
 * beyond the {@code state} anti-CSRF value Spring OAuth2 Client already
 * manages (Decisión 1).
 *
 * <p><b>Security requirement (Decisión 4):</b> {@link #findByRegistrationId}
 * returns {@code null} through the exact same code path — no distinguishing
 * exception, log line, or side effect — whether the {@link IdentityClient}
 * UUID doesn't exist at all, or it exists but the tenant never enabled that
 * provider ({@link TenantIdentityProvider#isEnabled()} false, or no row at
 * all). Both cases fall through the same {@code Optional} chain into the
 * same {@code orElse(null)}, on purpose: an attacker probing registrationIds
 * must not be able to tell "no such client" from "client exists, provider
 * off" from the response.
 *
 * <p>{@code redirectUri} is deliberately left as Spring's own
 * {@code "{baseUrl}/login/oauth2/code/{registrationId}"} placeholder
 * template (re-set explicitly here rather than relying on
 * {@link CommonOAuth2Provider}'s implicit default, so it doesn't silently
 * change if Spring's default ever does) — resolved per-request from the
 * actual incoming request, so it's correct in every environment without any
 * config. This already satisfies OQ-1: the exact same template string for
 * every tenant, only {@code registrationId} varies in the path.
 */
// java:S5673 — Sonar suggests @Repository because of the type name, but this
// isn't a Spring Data repository: it's a hand-rolled adapter to a Spring
// Security interface (same reasoning already applied to the sibling
// TenantAwareRegisteredClientRepository). @Repository would additionally
// opt this bean into Spring's persistence-exception-translation aspect,
// which doesn't apply here and isn't wanted.
@SuppressWarnings("java:S5673")
@Component
public class TenantAwareClientRegistrationRepository implements ClientRegistrationRepository {

    private static final String REDIRECT_URI_TEMPLATE = "{baseUrl}/login/oauth2/code/{registrationId}";

    private final IdentityClientRepository identityClientRepository;
    private final TenantIdentityProviderRepository tenantIdentityProviderRepository;
    private final TenantSecretEncryptor tenantSecretEncryptor;

    public TenantAwareClientRegistrationRepository(
            IdentityClientRepository identityClientRepository,
            TenantIdentityProviderRepository tenantIdentityProviderRepository,
            TenantSecretEncryptor tenantSecretEncryptor) {
        this.identityClientRepository = identityClientRepository;
        this.tenantIdentityProviderRepository = tenantIdentityProviderRepository;
        this.tenantSecretEncryptor = tenantSecretEncryptor;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return parse(registrationId)
                .flatMap(parsed -> identityClientRepository
                        .findById(parsed.identityClientId())
                        .map(IdentityClient::getTenant)
                        .flatMap(tenant -> tenantIdentityProviderRepository
                                .findByTenantAndProvider(tenant, parsed.provider())
                                .filter(TenantIdentityProvider::isEnabled)
                                .map(tenantIdentityProvider ->
                                        buildRegistration(registrationId, parsed, tenant, tenantIdentityProvider))))
                .orElse(null);
    }

    private ClientRegistration buildRegistration(
            String registrationId,
            ParsedRegistrationId parsed,
            Tenant tenant,
            TenantIdentityProvider tenantIdentityProvider) {
        String clientSecret =
                tenantSecretEncryptor.decrypt(tenant.getWrappedDataKey(), tenantIdentityProvider.getClientSecretEncrypted());
        return parsed.commonProvider()
                .getBuilder(registrationId)
                .clientId(tenantIdentityProvider.getClientId())
                .clientSecret(clientSecret)
                .redirectUri(REDIRECT_URI_TEMPLATE)
                .build();
    }

    private Optional<ParsedRegistrationId> parse(String registrationId) {
        // Shared with SocialLoginSuccessHandler/SocialLoginFailureHandler
        // (ticket 037) — see SocialRegistrationId's Javadoc. This class keeps
        // the extra CommonOAuth2Provider mapping step, which only it needs.
        return SocialRegistrationId.parse(registrationId).flatMap(parsed -> {
            CommonOAuth2Provider commonProvider = toCommonProvider(parsed.provider());
            if (commonProvider == null) {
                // APPLE is out of scope (docs/definiciones/login-social-real.md): no
                // CommonOAuth2Provider entry exists for it, and
                // TenantIdentityProviderService already refuses to ever enable it —
                // falls through to the same null as any other unresolvable id.
                return Optional.empty();
            }
            return Optional.of(new ParsedRegistrationId(parsed.identityClientId(), parsed.provider(), commonProvider));
        });
    }

    private static CommonOAuth2Provider toCommonProvider(IdentityProviderType provider) {
        return switch (provider) {
            case GOOGLE -> CommonOAuth2Provider.GOOGLE;
            case FACEBOOK -> CommonOAuth2Provider.FACEBOOK;
            case APPLE -> null;
        };
    }

    private record ParsedRegistrationId(UUID identityClientId, IdentityProviderType provider, CommonOAuth2Provider commonProvider) {}
}
