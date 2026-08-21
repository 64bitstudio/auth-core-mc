package com.mcortes.authcoremc.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security config so /register and /login (which by definition run
 * before a user has any credentials) aren't blocked by Spring Security's
 * default "authenticate everything" behavior. This is a placeholder:
 * ticket 007 (Spring Authorization Server) replaces/extends this with the
 * real OAuth2/OIDC security configuration.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                                "/js/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(basic -> {});
        return http.build();
    }
}
