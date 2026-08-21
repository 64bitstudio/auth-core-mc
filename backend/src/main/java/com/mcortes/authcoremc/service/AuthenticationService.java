package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.IdentifierFormat;
import com.mcortes.authcoremc.security.LoginRateLimiter;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Verifies email/phone + password credentials (the "first-party direct
 * login" grant — see docs/ARQUITECTURA.md decision 3). Deliberately does
 * NOT mint tokens: real OAuth2/JWT issuance belongs to ticket 007's
 * Authorization Server integration. This service's job ends at "these
 * credentials are valid for this user," which ticket 007 will call before
 * issuing a token.
 */
@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public AuthenticationService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, LoginRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
    }

    public User authenticate(Tenant tenant, String identifier, String rawPassword) {
        String tenantKey = tenant.getId().toString();
        rateLimiter.checkAllowed(tenantKey, identifier);

        Optional<User> found = IdentifierFormat.isValidEmail(identifier)
                ? userRepository.findByTenantAndEmail(tenant, identifier)
                : userRepository.findByTenantAndPhone(tenant, identifier);

        boolean valid = found.isPresent()
                && found.get().getPasswordHash() != null
                && passwordEncoder.matches(rawPassword, found.get().getPasswordHash());

        if (!valid) {
            rateLimiter.recordFailure(tenantKey, identifier);
            throw new InvalidCredentialsException();
        }

        rateLimiter.recordSuccess(tenantKey, identifier);
        return found.get();
    }
}
