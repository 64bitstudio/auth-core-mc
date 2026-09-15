package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 064 ("Mi Perfil", galgoth-studio, docs/definiciones/perfil-de-usuario.md
 * §HU-10): elimina (soft-delete/deactivate) la cuenta del usuario
 * autenticado. Orden estricto, en este orden exacto:
 * <ol>
 *   <li>Valida el paso de confirmación explícito (el propio email/teléfono
 *       reenviado) -- nunca por un clic accidental.
 *   <li>Purga SÍNCRONAMENTE los proyectos de galgoth-studio
 *       ({@link GalgothStudioPurgeClient}) -- si esta llamada falla,
 *       {@link AccountDeletionFailedException} se propaga y el
 *       {@code @Transactional} de este método revierte cualquier cambio
 *       (en este punto, ninguno todavía): la cuenta NUNCA queda
 *       desactivada con proyectos públicos todavía visibles en Explorar
 *       (decisión explícita de Marco).
 *   <li>Solo si el paso anterior tuvo éxito: {@code User.deactivate()} +
 *       revoca TODOS los refresh tokens del usuario (reutiliza
 *       {@code AccountSessionsService.revokeOthers} con
 *       {@code currentRawRefreshToken=null}, que revoca todas las sesiones
 *       sin excepción -- mismo método que ya usa "Cerrar sesión en todos
 *       los dispositivos", ticket 062, sin duplicar esa lógica).
 * </ol>
 */
@Service
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final GalgothStudioPurgeClient galgothStudioPurgeClient;
    private final AccountSessionsService accountSessionsService;

    public AccountDeletionService(
            UserRepository userRepository,
            GalgothStudioPurgeClient galgothStudioPurgeClient,
            AccountSessionsService accountSessionsService) {
        this.userRepository = userRepository;
        this.galgothStudioPurgeClient = galgothStudioPurgeClient;
        this.accountSessionsService = accountSessionsService;
    }

    @Transactional
    public void deleteAccount(UUID userId, String confirmIdentifier) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        requireMatchingIdentifier(user, confirmIdentifier);

        galgothStudioPurgeClient.purgeProjects(userId);

        user.deactivate();
        userRepository.save(user);
        accountSessionsService.revokeOthers(userId, null);
    }

    private void requireMatchingIdentifier(User user, String confirmIdentifier) {
        boolean matchesEmail = user.getEmail() != null && user.getEmail().equalsIgnoreCase(confirmIdentifier);
        boolean matchesPhone = user.getPhone() != null && user.getPhone().equals(confirmIdentifier);
        if (!matchesEmail && !matchesPhone) {
            throw new ConfirmationMismatchException();
        }
    }
}
