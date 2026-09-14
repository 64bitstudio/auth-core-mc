package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.PasswordPolicy;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 061 ("Mi Perfil", galgoth-studio, docs/definiciones/perfil-de-usuario.md
 * §HU-5): lets an authenticated user WITH a password change it, confirming
 * the current one — the third case alongside {@link SetPasswordService}
 * (first-time, social-only) and {@link PasswordResetService} (forgot my
 * password, no session). Deliberately does NOT revoke other sessions —
 * that is HU-6/ticket 062's "cerrar sesión en todos los dispositivos",
 * a separate explicit action per the definition document.
 */
@Service
public class ChangePasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ChangePasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        if (user.getPasswordHash() == null) {
            throw new NoPasswordSetException();
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IncorrectCurrentPasswordException();
        }
        PasswordPolicy.validate(newPassword);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }
}
