package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.IdentityClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Ticket 055: the one piece of logic {@code SocialLoginSuccessHandler} (two
 * call sites) and {@code SocialLoginFailureHandler} (one call site) all
 * need — where does the final redirect for a social-login outcome go?
 * {@code IdentityClient.ownLoginUiRedirectUri()} decides that; this builds
 * the actual URL around it, so none of the three call sites repeats the
 * branch itself (the first draft of this ticket had all three doing so).
 */
final class SocialLoginRedirect {

    private SocialLoginRedirect() {}

    /**
     * @param hostedPath the auth-core-mc page to fall back to when {@code
     *     identityClient} does not host its own login UI (or has no {@code
     *     redirect_uri} configured despite the flag) — always gets {@code
     *     ?client_id=} appended, matching the hosted pages' existing
     *     contract (ticket 039); a client's own {@code redirect_uri} never
     *     gets {@code client_id} appended (the URL is already client-specific).
     * @param paramName {@code "code"} on success, {@code "error"} on failure.
     */
    static String buildUri(IdentityClient identityClient, String hostedPath, String paramName, String paramValue) {
        UriComponentsBuilder builder = identityClient
                .ownLoginUiRedirectUri()
                .map(UriComponentsBuilder::fromUriString)
                .orElseGet(() ->
                        UriComponentsBuilder.fromPath(hostedPath).queryParam("client_id", identityClient.getClientId()));
        return builder.queryParam(paramName, paramValue).encode().build().toUriString();
    }
}
