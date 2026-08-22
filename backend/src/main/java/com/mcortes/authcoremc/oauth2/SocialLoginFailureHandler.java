package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.service.LoginEventRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What happens when the Google/Facebook callback does NOT hand back a
 * confirmed identity (ticket 037, HU-3, docs/definiciones/login-social-real.md
 * — see the "Camino de fallo" sequence diagram there). Two genuinely
 * different cases, deliberately handled differently — both the ticket and
 * the definition doc are explicit about this, so this class does NOT
 * collapse them into one generic page:
 *
 * <ol>
 *   <li><b>Consent denied</b> ({@code error=access_denied} — the user clicked
 *   "Cancel"/"Deny" on the provider's own consent screen): the {@code
 *   OAuth2AuthorizationRequest} DID correlate correctly (the session was
 *   valid, {@code state} matched) — this is a real, trustworthy continuation
 *   of a flow this app itself started, so the {@code registrationId} in the
 *   callback URL path is safe to parse and resolve. Redirects back to {@code
 *   /ui/login} for that same tenant, themed, with an {@code error} query
 *   param — ticket 039 wires {@code login.html} to read it and call the
 *   existing {@code showStatus()}.</li>
 *   <li><b>Everything else</b> — no correlated {@code
 *   OAuth2AuthorizationRequest} at all (expired session, tampered/replayed
 *   callback — Spring fails closed BEFORE this handler or any tenant/user
 *   code runs, Decisión 4), or a technical failure talking to the provider
 *   (e.g. the tenant's secret is expired/revoked): a generic, unthemed
 *   error page. Deliberately never infers {@code client_id} from the
 *   {@code Referer} header for this bucket — Decisión 4 is explicit that
 *   this case must not touch tenant resolution at all.</li>
 * </ol>
 *
 * <p><b>Placeholder route:</b> {@code /ui/social-login-error} has no
 * template yet — that page (unthemed, per HU-3) is ticket 039's job. This
 * redirect target is chosen now so 039 has a stable contract to build
 * against; until then the redirect 404s, which is expected and acceptable
 * for this ticket (037 is scoped to the handlers, not the UI).
 */
@Component
public class SocialLoginFailureHandler implements AuthenticationFailureHandler {

    static final String GENERIC_ERROR_PATH = "/ui/social-login-error";
    private static final String LOGIN_PATH = "/ui/login";

    private final IdentityClientRepository identityClientRepository;
    private final LoginEventRecorder loginEventRecorder;

    public SocialLoginFailureHandler(
            IdentityClientRepository identityClientRepository, LoginEventRecorder loginEventRecorder) {
        this.identityClientRepository = identityClientRepository;
        this.loginEventRecorder = loginEventRecorder;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (isConsentDenied(exception)) {
            Optional<SocialRegistrationId> parsed = SocialRegistrationId.parse(registrationIdFromPath(request));
            Optional<IdentityClient> client =
                    parsed.flatMap(p -> identityClientRepository.findById(p.identityClientId()));
            if (parsed.isPresent() && client.isPresent()) {
                loginEventRecorder.recordFailure(client.get().getTenant(), parsed.get().provider().name(), 0);
                response.sendRedirect(UriComponentsBuilder.fromPath(LOGIN_PATH)
                        .queryParam("client_id", client.get().getClientId())
                        .queryParam("error", "social_login_cancelled")
                        .encode()
                        .build()
                        .toUriString());
                return;
            }
            // Correlated request but registrationId no longer resolves (e.g.
            // the provider got disabled mid-flow) — falls through to the
            // generic page below rather than guessing a tenant.
        }

        // Session expired / callback manipulated (no correlated
        // OAuth2AuthorizationRequest — Decisión 4) and broken tenant
        // credentials both land here: no LoginEvent, since tenant was
        // deliberately never resolved for these cases.
        response.sendRedirect(GENERIC_ERROR_PATH);
    }

    private static boolean isConsentDenied(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oAuth2Exception
                && oAuth2Exception.getError() != null
                && OAuth2ErrorCodes.ACCESS_DENIED.equals(oAuth2Exception.getError().getErrorCode());
    }

    /**
     * Spring's own fixed callback path is {@code
     * /login/oauth2/code/{registrationId}} (see {@code
     * UiPagesController#OAUTH2_REDIRECT_URI_PATH}'s Javadoc) — the last path
     * segment is always the registrationId verbatim, whether the request
     * succeeded or failed, since it's set from the redirect Spring itself
     * generated. No URL-decoding needed: {@code "{uuid}::{provider}"}
     * contains no characters that require it.
     */
    private static String registrationIdFromPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int lastSlash = uri.lastIndexOf('/');
        return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
    }
}
