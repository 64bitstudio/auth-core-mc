package com.mcortes.authcoremc.security;

import tools.jackson.databind.ObjectMapper;
import com.mcortes.authcoremc.web.ErrorResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security config so /register and /login (which by definition run
 * before a user has any credentials) aren't blocked by Spring Security's
 * default "authenticate everything" behavior. This is a placeholder:
 * ticket 007 (Spring Authorization Server) replaces/extends this with the
 * real OAuth2/OIDC security configuration.
 *
 * <p>⚠️ No {@code httpBasic()} here, on purpose: this app has no actual HTTP
 * Basic auth flow anywhere — {@code httpBasic()}'s only real effect was
 * making every 401 carry a {@code WWW-Authenticate: Basic} header, which
 * makes browsers pop up their own native username/password dialog. That
 * happened for two real, reported symptoms: (1) {@code /favicon.ico},
 * requested by every browser on every page load, wasn't in {@code
 * permitAll}; (2) ANY unhandled exception on a {@code permitAll}'d
 * endpoint (e.g. a missing {@code X-Client-Id} header) makes the servlet
 * container forward internally to {@code /error} — a NEW request through
 * this same filter chain — and since {@code /error} wasn't in {@code
 * permitAll} either, that forward got the same 401 challenge, masking the
 * real error behind a login popup instead of the actual status code
 * (usually 400). This is the same failure shape ticket 007 found with a
 * missing BouncyCastle dependency — that time the specific cause got
 * fixed without fixing the general masking mechanism; this time it's
 * fixed at the root instead. A custom {@code AuthenticationEntryPoint}
 * (still 401, no challenge header, JSON body matching the rest of the
 * API) is used instead, for any request that IS still deliberately
 * unauthenticated on purpose (like {@code /identity-providers/**}, see
 * ticket 006).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/register",
                                "/api/v1/login",
                                "/api/v1/verify-email/**",
                                "/api/v1/change-email/**",
                                "/api/v1/password-reset/**",
                                "/api/v1/2fa/**",
                                "/api/v1/token/**",
                                "/actuator/health",
                                // Ticket 009: server-rendered pages and their static assets. Public
                                // by design — /ui/cuenta's own guard is client-side (see its
                                // Javadoc in UiPagesController), not enforced here, since none of
                                // these pages are backed by a real Spring Security session yet.
                                "/ui/**",
                                "/css/**",
                                "/js/**",
                                // Requested by every browser on every page load — never something
                                // to gate behind authentication.
                                "/favicon.ico",
                                // The servlet container's internal forward target for any
                                // unhandled exception (see class Javadoc) — must stay open or it
                                // masks the real error behind a 401 challenge.
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    writeJson(response, objectMapper, new ErrorResponse("unauthorized", "Authentication required"));
                }));
        return http.build();
    }

    private static void writeJson(
            jakarta.servlet.http.HttpServletResponse response, ObjectMapper objectMapper, ErrorResponse body)
            throws IOException {
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
