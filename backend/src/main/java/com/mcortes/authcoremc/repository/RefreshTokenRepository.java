package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Ticket 013: TenantPurgeService's dependency-ordered physical delete — refresh_token has no direct tenant_id, only via its user. */
    List<RefreshToken> findByUserIn(List<User> users);

    /** Ticket 062 -- "Sesiones activas": solo las utilizables ahora mismo (ni revocadas ni expiradas), más recientes primero. */
    List<RefreshToken> findByUserAndRevokedFalseAndExpiresAtAfterOrderByLastUsedAtDesc(User user, Instant now);

    /** Ticket 062 -- ownership check para revocar una sesión individual: nunca opera sobre la fila de otro usuario. */
    Optional<RefreshToken> findByIdAndUser(UUID id, User user);
}
