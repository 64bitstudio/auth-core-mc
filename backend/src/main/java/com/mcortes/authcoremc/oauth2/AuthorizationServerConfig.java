package com.mcortes.authcoremc.oauth2;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Wires the {@code /oauth2/**} and {@code /.well-known/**} endpoints
 * (authorize, token, jwks, revoke, OIDC discovery/userinfo). This filter
 * chain is evaluated BEFORE {@link com.mcortes.authcoremc.security.SecurityConfig}'s
 * ({@code @Order(1)} vs. the default lowest precedence), scoped only to
 * those paths — everything else still goes through the plain REST API's
 * security rules.
 *
 * <p>⚠️ {@code /oauth2/authorize} redirects an unauthenticated browser to
 * Spring's default login form, which this project doesn't have a real page
 * behind yet — the actual login UI is ticket 009. The token endpoint,
 * JWKS, and OIDC discovery metadata all work standalone today; only the
 * full authorization_code browser flow needs that UI to actually complete.
 *
 * <p>⚠️ The RSA signing key is generated fresh at every application
 * startup — a deliberate, documented simplification (see docs/README.md):
 * tokens issued before a restart won't verify after one. A real deployment
 * needs a persisted, rotated key.
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        // Spring Security 7.1 removed the old applyDefaultSecurity(HttpSecurity)
        // static helper; .oauth2AuthorizationServer(...) is the new first-class
        // DSL entry point (alongside .oauth2Login(...), .oauth2ResourceServer(...)).
        //
        // getEndpointsMatcher() is NOT stateless the way it first looks: a
        // throwaway `new OAuth2AuthorizationServerConfigurer()` was never
        // attached to this `http` builder, so its internal matcher field stays
        // null forever and every request 500s with an NPE (found by actually
        // running the app, not just letting @SpringBootTest's contextLoads()
        // pass — that test never sends a real HTTP request through the chain).
        // The fix: apply the DSL first, then pull the SAME instance back out
        // via getConfigurer() before asking it for its matcher.
        http.oauth2AuthorizationServer(authorizationServer -> authorizationServer.oidc(Customizer.withDefaults()));
        RequestMatcher endpointsMatcher =
                http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).getEndpointsMatcher();
        http.securityMatcher(endpointsMatcher);
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        return AuthorizationServerSettings.builder().issuer(baseUrl).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate the authorization server's RSA signing key", e);
        }
    }
}
