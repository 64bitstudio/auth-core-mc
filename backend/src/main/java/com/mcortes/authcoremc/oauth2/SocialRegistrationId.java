package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses a Spring Security {@code registrationId} of the shape
 * {@code "{identityClientId}::{provider}"} (Diseño técnico, decisión 1,
 * docs/definiciones/login-social-real.md) into its two parts.
 *
 * <p>Shared by {@link TenantAwareClientRegistrationRepository} (which
 * resolves it into a {@code ClientRegistration} at redirect/callback time,
 * ticket 036) and {@code SocialLoginSuccessHandler}/{@code
 * SocialLoginFailureHandler} (ticket 037, which need the same tenant+
 * provider identity again once the OAuth2 dance with Google/Facebook has
 * already completed) — one parser, so the {@code "::"} format only has to
 * be right in one place. Extracted out of {@code
 * TenantAwareClientRegistrationRepository} specifically for this reuse; that
 * class still owns the extra step of mapping the provider to a Spring
 * {@code CommonOAuth2Provider}, which only it needs.
 */
public final class SocialRegistrationId {

    private static final String SEPARATOR = "::";

    private final UUID identityClientId;
    private final IdentityProviderType provider;

    private SocialRegistrationId(UUID identityClientId, IdentityProviderType provider) {
        this.identityClientId = identityClientId;
        this.provider = provider;
    }

    /**
     * Ticket 044: the inverse of {@link #parse} — builds the exact
     * {@code registrationId} string for a given tenant's {@code
     * IdentityClient} + provider. Used to show an admin the real, concrete
     * {@code redirect_uri} to register in Google/Facebook's own console
     * (see {@code UiPagesController#adminIdentityProviders}) — one
     * formatter, so the {@code "::"}/lowercase-provider format only has to
     * be right in this one place, same reasoning as {@link #parse}.
     */
    public static SocialRegistrationId of(UUID identityClientId, IdentityProviderType provider) {
        return new SocialRegistrationId(identityClientId, provider);
    }

    @Override
    public String toString() {
        return identityClientId + SEPARATOR + provider.name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SocialRegistrationId> parse(String registrationId) {
        if (registrationId == null) {
            return Optional.empty();
        }
        int separatorIndex = registrationId.indexOf(SEPARATOR);
        if (separatorIndex < 0) {
            return Optional.empty();
        }

        UUID identityClientId;
        try {
            identityClientId = UUID.fromString(registrationId.substring(0, separatorIndex));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }

        IdentityProviderType provider;
        try {
            provider = IdentityProviderType.valueOf(
                    registrationId.substring(separatorIndex + SEPARATOR.length()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }

        return Optional.of(new SocialRegistrationId(identityClientId, provider));
    }

    public UUID identityClientId() {
        return identityClientId;
    }

    public IdentityProviderType provider() {
        return provider;
    }
}
