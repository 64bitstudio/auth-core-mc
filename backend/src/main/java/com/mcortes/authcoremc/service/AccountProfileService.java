package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 060 ("Mi Perfil", galgoth-studio, docs/definiciones/perfil-de-usuario.md):
 * edita nombre/apellidos/país/nombre de usuario del usuario autenticado.
 * `username` es único por tenant — chequeado aquí ANTES de guardar (mismo
 * criterio que {@code RegistrationService} con email/phone) para devolver
 * un error claro en vez de dejar que la violación del CHECK de la BD
 * (defensa en profundidad, no el camino principal) se traduzca en un error
 * genérico.
 */
@Service
public class AccountProfileService {

    private final UserRepository userRepository;

    public AccountProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User updateProfile(UUID userId, String nombre, String apellidos, String country, String username) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        if (username != null && !username.isBlank() && !username.equals(user.getUsername())) {
            userRepository.findByTenantAndUsername(user.getTenant(), username).ifPresent(existing -> {
                throw new DuplicateIdentifierException("This username is already taken");
            });
        }

        user.updateProfile(nombre, apellidos, country, username);
        return userRepository.save(user);
    }
}
