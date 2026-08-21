package com.mcortes.authcoremc.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Ticket 012: maps the access token's {@code role} claim (see {@code
 * AdminClaimsCustomizer}) to a Spring Security authority — {@code
 * ROLE_TENANT_ADMIN} / {@code ROLE_PLATFORM_ADMIN}. {@code NONE} (the
 * default for every regular end user) or a missing claim entirely both
 * map to no authorities — never a fail-open default.
 */
public class AdminRoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        if (role == null || role.equals("NONE")) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
