package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.LoginEvent;
import com.mcortes.authcoremc.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    /** Ticket 013: TenantPurgeService's dependency-ordered physical delete. */
    List<LoginEvent> findByTenant(Tenant tenant);
}
