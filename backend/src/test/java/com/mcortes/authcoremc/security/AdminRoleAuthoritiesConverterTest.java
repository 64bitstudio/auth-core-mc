package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Ticket 012: maps the access token's {@code role} claim (see
 * {@code AdminClaimsCustomizer}) to a Spring Security authority, so
 * {@code SecurityConfig}'s admin-route rule can gate on {@code
 * hasAnyRole(...)} without decoding the claim itself in the filter chain.
 */
class AdminRoleAuthoritiesConverterTest {

    private final AdminRoleAuthoritiesConverter converter = new AdminRoleAuthoritiesConverter();

    private Jwt jwtWithRole(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claim("sub", "some-user-id");
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }

    @Test
    void tenantAdminRoleMapsToItsOwnAuthority() {
        var authorities = converter.convert(jwtWithRole("TENANT_ADMIN"));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_TENANT_ADMIN");
    }

    @Test
    void platformAdminRoleMapsToItsOwnAuthority() {
        var authorities = converter.convert(jwtWithRole("PLATFORM_ADMIN"));

        assertThat(authorities).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_PLATFORM_ADMIN");
    }

    @Test
    void noneRoleMapsToNoAuthorities() {
        var authorities = converter.convert(jwtWithRole("NONE"));

        assertThat(authorities).isEmpty();
    }

    @Test
    void aTokenWithoutARoleClaimAtAllMapsToNoAuthorities() {
        var authorities = converter.convert(jwtWithRole(null));

        assertThat(authorities).isEmpty();
    }
}
