package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Ticket 013: TenantPurgeService's dependency-ordered physical delete — refresh_token has no direct tenant_id, only via its user. */
    List<RefreshToken> findByUserIn(List<User> users);
}
