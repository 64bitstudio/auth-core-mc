package com.mcortes.authcoremc.oauth2;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;

/**
 * The same JWT-minting machinery Spring Authorization Server's own
 * authorization_code/refresh_token grants use internally for access
 * tokens, exposed as a bean so
 * {@link com.mcortes.authcoremc.service.DirectTokenService} can mint a
 * real, correctly-signed access token for the first-party direct login
 * (ticket 002/007) without an HTTP round-trip to {@code /oauth2/token} —
 * there's no separate "direct" grant type registered with Spring; this
 * calls the exact same {@link JwtGenerator} with a manually-built context
 * instead. The refresh token is deliberately NOT minted this way — see
 * {@code DirectTokenService}'s Javadoc for why it's a plain opaque token
 * from our own {@code refresh_token} table instead.
 */
@Configuration
public class TokenGeneratorConfig {

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtGenerator jwtGenerator(JwtEncoder jwtEncoder) {
        JwtGenerator generator = new JwtGenerator(jwtEncoder);
        // Ticket 012: stamps role/tenant_id claims for the admin panel's role
        // gate — see AdminClaimsCustomizer.
        generator.setJwtCustomizer(new AdminClaimsCustomizer());
        return generator;
    }
}
