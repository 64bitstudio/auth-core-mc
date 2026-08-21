package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.TenantRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticket 013: the {@code @Scheduled} entry point for {@link TenantPurgeService#purge},
 * deliberately kept in its own bean rather than folded into {@code TenantPurgeService}
 * itself.
 *
 * <p>Real SonarQube finding (java:S2229 BLOCKER / java:S6809), not just a style nit:
 * if this loop called {@code this.purge(tenant)} from inside {@code TenantPurgeService},
 * that would be a same-class self-invocation, which bypasses Spring's AOP proxy —
 * {@code @Transactional} is only applied to calls that arrive through the proxy, so
 * the purge would silently run without transaction boundaries, risking a partial
 * delete across the FK-dependency chain if one step failed midway. Calling into a
 * separate bean forces the call through the real proxy.
 */
@Component
public class TenantPurgeScheduler {

    private final TenantRepository tenantRepository;
    private final TenantPurgeService purgeService;

    public TenantPurgeScheduler(TenantRepository tenantRepository, TenantPurgeService purgeService) {
        this.tenantRepository = tenantRepository;
        this.purgeService = purgeService;
    }

    /** Runs daily at 03:00 — low-traffic hour, no real-time requirement for a 90-day-old cutoff. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeEligibleTenants() {
        List<Tenant> eligible = tenantRepository.findAll().stream()
                .filter(purgeService::isEligibleForPurge)
                .toList();
        for (Tenant tenant : eligible) {
            purgeService.purge(tenant);
        }
    }
}
