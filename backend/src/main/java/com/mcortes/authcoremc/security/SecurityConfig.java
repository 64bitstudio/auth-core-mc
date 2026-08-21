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
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/register", "/api/v1/login", "/actuator/health")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(basic -> {});
        return http.build();
    }
}
