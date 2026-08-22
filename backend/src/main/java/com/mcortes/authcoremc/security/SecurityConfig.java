package com.mcortes.authcoremc.security;

import tools.jackson.databind.ObjectMapper;
import com.mcortes.authcoremc.oauth2.SocialLoginFailureHandler;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.web.ErrorResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
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
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            ClientRegistrationRepository clientRegistrationRepository,
            SocialLoginSuccessHandler socialLoginSuccessHandler,
            SocialLoginFailureHandler socialLoginFailureHandler) {
        try {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.requestMatchers(
                                    "/api/v1/register",
                                    "/api/v1/login",
                                    "/api/v1/verify-email/**",
                                    "/api/v1/change-email/**",
                                    "/api/v1/password-reset/**",
                                    "/api/v1/2fa/**",
                                    "/api/v1/token/**",
                                    // Ticket 018: break-glass — deliberately NOT gated by the JWT/role
                                    // rule below, on purpose: it exists specifically for when that
                                    // machinery is what's broken. Its own three-factor check
                                    // (BreakGlassService) is the real gate, not Spring Security.
                                    "/api/v1/breakglass/**",
                                    "/actuator/health",
                                    // Ticket 009: server-rendered pages and their static assets. Public
                                    // by design — /ui/cuenta's own guard is client-side (see its
                                    // Javadoc in UiPagesController), not enforced here, since none of
                                    // these pages are backed by a real Spring Security session yet.
                                    "/ui/**",
                                    "/css/**",
                                    "/js/**",
                                    // Ticket 036: client-side OAuth2 login (Google/Facebook social
                                    // login) — the redirect-to-provider endpoint and the provider's
                                    // callback endpoint. Both are unauthenticated by definition (a
                                    // user arriving here has no session/token yet); real tenant
                                    // resolution happens inside
                                    // TenantAwareClientRegistrationRepository, not here.
                                    // AuthorizationServerConfig's own filter chain (@Order(1),
                                    // securityMatcher scoped to getEndpointsMatcher()) already
                                    // excludes these paths structurally — see its Javadoc — so this
                                    // permitAll doesn't overlap with that chain at all.
                                    "/oauth2/authorization/**",
                                    "/login/oauth2/code/**",
                                    // Ticket 038: canjea el código de un solo uso por tokens reales —
                                    // público por definición (es el paso que OTORGA la sesión, no uno
                                    // que la requiere). El código de un solo uso es la única credencial
                                    // válida aquí, ver SocialExchangeController.
                                    "/api/v1/oauth2/social-exchange",
                                    // Requested by every browser on every page load — never something
                                    // to gate behind authentication.
                                    "/favicon.ico",
                                    // The servlet container's internal forward target for any
                                    // unhandled exception (see class Javadoc) — must stay open or it
                                    // masks the real error behind a 401 challenge.
                                    "/error")
                            .permitAll()
                            // Ticket 012: admin-panel routes need an admin role, not just
                            // "any authenticated user" — checked BEFORE the anyRequest()
                            // catch-all below. No admin endpoints exist yet (ticket 013+
                            // builds them under this same prefix); this rule is proven
                            // generically here, not against a real admin route yet.
                            .requestMatchers("/api/v1/admin/**")
                            .hasAnyRole("TENANT_ADMIN", "PLATFORM_ADMIN")
                            .anyRequest()
                            .authenticated())
                    // Ticket 012: wires the JwtDecoder (already defined in
                    // AuthorizationServerConfig, but never actually connected to this
                    // chain until now) so a Bearer JWT authenticates a request at all —
                    // without this, IdentityProviderController's .anyRequest().authenticated()
                    // (ticket 006) had no real way to be satisfied outside of tests'
                    // @WithMockUser. The role claim (AdminClaimsCustomizer) becomes a
                    // Spring authority via AdminRoleAuthoritiesConverter.
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
                        converter.setJwtGrantedAuthoritiesConverter(new AdminRoleAuthoritiesConverter());
                        jwt.jwtAuthenticationConverter(converter);
                    }))
                    // Ticket 036/037: end-user social login (Google/Facebook), resolved
                    // per request/per tenant by TenantAwareClientRegistrationRepository —
                    // see its Javadoc and docs/definiciones/login-social-real.md.
                    // SocialLoginSuccessHandler/SocialLoginFailureHandler (ticket 037) own
                    // creating/linking app_user, issuing the one-time exchange code, and
                    // the themed-vs-generic error split (HU-3). The Spring Security
                    // session this DSL creates is NOT used as ongoing auth (see Decisión 5
                    // in the definition doc) — only as the correlation Spring itself needs
                    // between the redirect and the callback.
                    .oauth2Login(oauth2Login -> oauth2Login
                            .clientRegistrationRepository(clientRegistrationRepository)
                            .successHandler(socialLoginSuccessHandler)
                            .failureHandler(socialLoginFailureHandler))
                    .exceptionHandling(exceptions ->
                            exceptions.authenticationEntryPoint((request, response, authException) -> {
                                response.setStatus(401);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                writeJson(response, objectMapper, new ErrorResponse("unauthorized", "Authentication required"));
                            }));
            return http.build();
        } catch (Exception e) {
            // HttpSecurity.build() (and several DSL methods above it) declare a
            // broad `throws Exception` in Spring Security's own API — not
            // something this method can narrow. Catching it here and
            // rethrowing unchecked keeps this bean's own signature honest
            // (nothing it actually does throws a checked exception) instead
            // of just propagating the framework's broad type.
            throw new IllegalStateException("Failed to build the security filter chain", e);
        }
    }

    private static void writeJson(
            jakarta.servlet.http.HttpServletResponse response, ObjectMapper objectMapper, ErrorResponse body)
            throws IOException {
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
