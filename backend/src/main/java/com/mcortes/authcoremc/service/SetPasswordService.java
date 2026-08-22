package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.PasswordPolicy;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HU-5 (docs/definiciones/login-social-real.md): lets the authenticated
 * user set a password when their account doesn't have one yet — today a
 * manually-provisioned account (social login itself isn't wired until
 * tickets 038-040 land), from then on any social-only account. Reuses the
 * exact same {@link PasswordPolicy} and {@link PasswordEncoder} (Argon2id)
 * as {@link com.mcortes.authcoremc.service.RegistrationService} and
 * {@link PasswordResetService} — no separate strength rule or hashing path
 * for this flow.
 *
 * <p>Deliberately NOT a "change password" flow: an account that already
 * has a {@code password_hash} is rejected with {@link
 * PasswordAlreadySetException} rather than silently overwritten — that
 * would collapse two different operations (first-time set vs. change) with
 * very different trust requirements into one, exactly what the ticket
 * calls out as out of scope.
 */
@Service
public class SetPasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SetPasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User setPassword(UUID userId, String rawPassword) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        if (user.getPasswordHash() != null) {
            throw new PasswordAlreadySetException();
        }
        PasswordPolicy.validate(rawPassword);
        user.changePasswordHash(passwordEncoder.encode(rawPassword));
        return userRepository.save(user);
    }
}
