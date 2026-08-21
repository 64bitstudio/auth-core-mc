package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.LoginEvent;
import com.mcortes.authcoremc.domain.Tenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    /** Ticket 013: TenantPurgeService's dependency-ordered physical delete. */
    List<LoginEvent> findByTenant(Tenant tenant);

    /**
     * Ticket 016: raw material for {@code AdminMetricsService}'s
     * aggregation, done in Java rather than a DB-level aggregate query —
     * consistent with this codebase's low-volume assumption (see
     * docs/definiciones/panel-administracion-clientes.md) and its existing
     * "fetch then aggregate/filter in Java" style (e.g.
     * {@code TenantPurgeService}), not a new pattern introduced here.
     */
    List<LoginEvent> findByTenantAndOccurredAtBetween(Tenant tenant, Instant from, Instant to);

    /** Ticket 018: break-glass diagnostics — a global (not per-tenant) recent-activity snapshot. */
    List<LoginEvent> findTop10ByOrderByOccurredAtDesc();
}
