package com.mcortes.authcoremc.oauth2;

import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * Minimal {@link AuthorizationServerContext}. Normally Spring populates
 * {@code AuthorizationServerContextHolder} automatically for requests that
 * go through the {@code /oauth2/**} filter chain — but
 * {@link com.mcortes.authcoremc.service.DirectTokenService} mints a token
 * from an ordinary {@code /api/v1/login} request on a different filter
 * chain, so nothing sets that context for it. This is that minimal context,
 * set manually right before generating a token.
 */
public class SimpleAuthorizationServerContext implements AuthorizationServerContext {

    private final AuthorizationServerSettings settings;

    public SimpleAuthorizationServerContext(AuthorizationServerSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getIssuer() {
        return settings.getIssuer();
    }

    @Override
    public AuthorizationServerSettings getAuthorizationServerSettings() {
        return settings;
    }
}
