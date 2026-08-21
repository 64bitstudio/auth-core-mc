package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /** Ticket 013: pre-check for the real UNIQUE constraint on tenant.name — clearer error than a raw constraint violation. */
    Optional<Tenant> findByName(String name);

    /** Ticket 018: break-glass diagnostics. */
    long countByDeactivatedAtIsNull();
}
