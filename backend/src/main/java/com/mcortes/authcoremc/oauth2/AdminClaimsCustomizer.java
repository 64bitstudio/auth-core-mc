package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Ticket 012: stamps the authenticated user's admin-panel role and tenant
 * id onto every access token this {@link
 * org.springframework.security.oauth2.server.authorization.token.JwtGenerator}
 * mints — the admin panel's role gate (see {@code SecurityConfig}) reads
 * these claims straight off the token instead of doing a DB lookup per
 * request.
 *
 * <p>Reads the {@link User} off {@code context.getAuthorizationGrant()}'s
 * principal (see {@code DirectTokenService#generateAccessToken}) — NOT a
 * generic {@code context.put()}/{@code get()}, because {@code
 * JwtGenerator}'s internal {@code JwtEncodingContext.with(...)} only
 * copies specific recognized fields from the original context (confirmed
 * by reading its bytecode), and {@code authorizationGrant} is one of the
 * few that survives. Silently does nothing when absent, so this never
 * breaks a token-issuance path that has no user in scope.
 */
public class AdminClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        Authentication authorizationGrant = context.getAuthorizationGrant();
        if (!(authorizationGrant != null && authorizationGrant.getPrincipal() instanceof User user)) {
            return;
        }
        context.getClaims().claim("role", user.getRole().name());
        context.getClaims().claim("tenant_id", user.getTenant().getId().toString());
    }
}
