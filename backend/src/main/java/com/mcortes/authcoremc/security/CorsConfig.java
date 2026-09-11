package com.mcortes.authcoremc.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Ticket 054: the direct JSON API (X-Client-Id, ticket 002) is meant to be
 * called from a browser-hosted SPA that isn't served by this app (e.g.
 * galgoth-studio's own frontend, verified against a real preflight before
 * this ticket — no {@code Access-Control-Allow-Origin} at all, so the
 * browser blocked every cross-origin call). Scoped to {@code /api/v1/**}
 * only, on purpose: {@code /oauth2/jwks} is fetched server-to-server by
 * each client's own backend (see the PROP-GS-AUTH-01 resource-server
 * design), never by a browser, so it needs no CORS exposure; {@code /ui/**}
 * is server-rendered and navigated to directly, never {@code fetch()}'d
 * cross-origin.
 *
 * <p>Allowlist, not a wildcard/pattern — explicit decision (galgoth-studio
 * blueprint, section 08): exact origins only, one per client. Empty by
 * default (fail closed, same convention as {@code BreakGlassService}'s
 * {@code allowed-ips}) — no existing deployment behavior changes until an
 * operator sets {@code CORS_ALLOWED_ORIGINS} explicitly.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOriginsCsv) {
        this.allowedOrigins = allowedOriginsCsv == null || allowedOriginsCsv.isBlank()
                ? List.of()
                : Arrays.stream(allowedOriginsCsv.split(",")).map(String::trim).toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // An empty allowedOrigins list (the default) matches no origin at
        // all — CorsConfiguration treats "no origins configured" as "CORS
        // disabled for this mapping", not "allow everything".
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-Client-Id", "Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }
}
