package com.mcortes.authcoremc.web;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Shared by every controller that needs the calling client's id from a
 * verified JWT's {@code aud} claim instead of a separate {@code
 * X-Client-Id} header (first used by {@link AccountLinkProviderController},
 * now also {@link EmailChangeController}/{@link EmailVerificationController}
 * -- extracted here so a 3rd/4th copy of the same null/empty check doesn't
 * accumulate, same duplication Sonar already flagged once in this area,
 * ticket 055's Hecho section).
 */
final class JwtAudience {

    private JwtAudience() {}

    static String firstClientId(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        return (audience == null || audience.isEmpty()) ? null : audience.get(0);
    }
}
