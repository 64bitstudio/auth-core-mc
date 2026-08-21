package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.IdentifierFormat;
import com.mcortes.authcoremc.security.PasswordPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers a new user with email-or-phone + password. The "at least one
 * identifier" rule is enforced twice by design, not by accident: here early
 * (fail fast, in memory, with a clear message) and again by {@link User}'s
 * constructor plus the database CHECK constraint (defense in depth — see
 * docs/ARQUITECTURA.md).
 */
@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(
            Tenant tenant, String email, String phone, String nombre, String apellidos, String rawPassword) {
        String normalizedEmail = blankToNull(email);
        String normalizedPhone = blankToNull(phone);

        if (normalizedEmail != null && !IdentifierFormat.isValidEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Invalid email format");
        }
        if (normalizedPhone != null && !IdentifierFormat.isValidPhone(normalizedPhone)) {
            throw new IllegalArgumentException("Invalid phone format");
        }
        PasswordPolicy.validate(rawPassword);

        String passwordHash = passwordEncoder.encode(rawPassword);
        try {
            return userRepository.save(
                    new User(tenant, normalizedEmail, normalizedPhone, nombre, apellidos, passwordHash));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateIdentifierException("Email or phone is already registered for this tenant");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
