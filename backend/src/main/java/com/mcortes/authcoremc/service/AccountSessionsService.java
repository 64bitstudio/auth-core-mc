package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.RefreshTokenRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.TokenHasher;
import com.mcortes.authcoremc.security.UserAgentParser;
import com.mcortes.authcoremc.web.SessionSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 062 ("Sesiones activas", Mi Perfil de galgoth-studio). "Actual" se
 * determina comparando el hash del refresh token crudo que el propio
 * caller manda (header {@code X-Current-Refresh-Token}, opcional) contra
 * {@code token_hash} — el mismo mecanismo de comparación que
 * {@code DirectTokenService#refresh} ya usa, nunca se decodifica nada del
 * JWT de acceso (que no lleva ninguna referencia a qué refresh token lo
 * emitió).
 */
@Service
public class AccountSessionsService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AccountSessionsService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> list(UUID userId, String currentRawRefreshToken) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        String currentHash = currentRawRefreshToken == null ? null : TokenHasher.sha256(currentRawRefreshToken);

        return refreshTokenRepository.findByUserAndRevokedFalseAndExpiresAtAfterOrderByLastUsedAtDesc(user, Instant.now())
                .stream()
                .map(token -> toSummary(token, currentHash))
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID sessionId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        RefreshToken token = refreshTokenRepository.findByIdAndUser(sessionId, user).orElseThrow(SessionNotFoundException::new);
        token.revoke();
        refreshTokenRepository.save(token);
    }

    /** Revoca todas las sesiones del usuario EXCEPTO la que coincide con {@code currentRawRefreshToken} (si se manda). Sin ese header, revoca todas. */
    @Transactional
    public void revokeOthers(UUID userId, String currentRawRefreshToken) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        String currentHash = currentRawRefreshToken == null ? null : TokenHasher.sha256(currentRawRefreshToken);

        List<RefreshToken> sessions =
                refreshTokenRepository.findByUserAndRevokedFalseAndExpiresAtAfterOrderByLastUsedAtDesc(user, Instant.now());
        for (RefreshToken token : sessions) {
            if (currentHash != null && currentHash.equals(token.getTokenHash())) {
                continue;
            }
            token.revoke();
        }
        refreshTokenRepository.saveAll(sessions);
    }

    private SessionSummary toSummary(RefreshToken token, String currentHash) {
        boolean current = currentHash != null && currentHash.equals(token.getTokenHash());
        return new SessionSummary(
                token.getId(),
                UserAgentParser.browser(token.getUserAgent()),
                UserAgentParser.os(token.getUserAgent()),
                token.getCreatedAt(),
                token.getLastUsedAt(),
                current);
    }
}
