package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.service.ExternalIdentityLinkService;
import com.mcortes.authcoremc.service.UnsupportedProviderException;
import java.util.Locale;

/**
 * Shared by every endpoint that accepts a provider name as a path segment
 * (first used by {@link AccountLinkProviderController}, now also {@link
 * SocialLoginUrlController}) -- extracted so a 3rd copy of the same
 * parse-and-validate logic doesn't accumulate (same duplication concern
 * already resolved once for {@link JwtAudience}).
 */
final class SupportedProvider {

    private SupportedProvider() {}

    static IdentityProviderType parse(String raw) {
        IdentityProviderType provider;
        try {
            provider = IdentityProviderType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw new UnsupportedProviderException("Unknown provider: " + raw);
        }
        if (!ExternalIdentityLinkService.isSupported(provider)) {
            throw new UnsupportedProviderException("Not available yet for provider: " + provider);
        }
        return provider;
    }
}
