package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.TenantIdentityProvider;

/** Deliberately excludes client_secret_encrypted — never serialize it, not even encrypted. */
public record IdentityProviderView(String provider, boolean enabled, String clientId) {

    public static IdentityProviderView from(TenantIdentityProvider entity) {
        return new IdentityProviderView(entity.getProvider().name(), entity.isEnabled(), entity.getClientId());
    }
}
