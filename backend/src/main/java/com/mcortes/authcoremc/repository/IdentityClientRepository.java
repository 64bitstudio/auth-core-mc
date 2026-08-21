package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityClientRepository extends JpaRepository<IdentityClient, UUID> {

    Optional<IdentityClient> findByClientId(String clientId);

    /** Ticket 013: TenantPurgeService's dependency-ordered physical delete. */
    List<IdentityClient> findByTenant(Tenant tenant);
}
