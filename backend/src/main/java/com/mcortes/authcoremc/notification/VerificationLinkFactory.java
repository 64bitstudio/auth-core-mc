package com.mcortes.authcoremc.notification;

import com.mcortes.authcoremc.domain.IdentityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the clickable link sent in verification/change-email/reset emails.
 *
 * <p>Ticket 009: {@code hostedPath} points at the {@code /ui/**} HTML
 * confirmation pages (see {@code UiPagesController}), not the raw {@code
 * /api/v1/**} JSON endpoints — a person clicking a link in an email should
 * land on a page, not a bare JSON body. The page itself calls the JSON
 * endpoint via {@code fetch} once it loads (see {@code api.js}).
 *
 * <p>Ticket 056: {@code app.base-url} is only the fallback now — the same
 * gap ticket 055 closed for social login. A client with
 * {@code hosts_own_login_ui=true} gets this link built against its own
 * domain ({@link IdentityClient#ownUiOrigin()}) instead, at the same path
 * minus the {@code /ui} prefix (auth-core-mc hosts {@code
 * /ui/verify-email/confirm}; the client hosts {@code /verify-email/confirm}
 * on its own frontend). Any client without that flag keeps today's
 * behavior unchanged.
 */
@Component
public class VerificationLinkFactory {

    private static final String HOSTED_PREFIX = "/ui";

    private final String baseUrl;

    public VerificationLinkFactory(@Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * @param client the client whose request originated this link.
     * @param hostedPath e.g. "/ui/verify-email/confirm" — the auth-core-mc
     *     page that consumes the token when {@code client} does not host
     *     its own UI.
     */
    public String build(IdentityClient client, String hostedPath, String token) {
        return client.ownUiOrigin()
                        .map(origin -> origin + ownUiPath(hostedPath))
                        .orElse(baseUrl + hostedPath)
                + "?token=" + token;
    }

    private static String ownUiPath(String hostedPath) {
        return hostedPath.startsWith(HOSTED_PREFIX) ? hostedPath.substring(HOSTED_PREFIX.length()) : hostedPath;
    }
}
