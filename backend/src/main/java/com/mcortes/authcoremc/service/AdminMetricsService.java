package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.LoginEvent;
import com.mcortes.authcoremc.domain.LoginOutcome;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.AdminAccessPolicy;
import com.mcortes.authcoremc.web.TenantAccessDeniedException;
import com.mcortes.authcoremc.web.TenantMetricsResponse;
import com.mcortes.authcoremc.web.TenantNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Ticket 016: usage metrics for the admin panel, aggregated from {@code
 * login_event} (ticket 015). Same fine-grained access pattern as {@code
 * AdminTenantService} — {@link AdminAccessPolicy} decides purely from the
 * caller's role/tenant_id claims, no DB lookup of the caller needed.
 */
@Service
public class AdminMetricsService {

    private final TenantRepository tenantRepository;
    private final LoginEventRepository loginEventRepository;
    private final UserRepository userRepository;
    private final AdminAccessPolicy accessPolicy;

    public AdminMetricsService(
            TenantRepository tenantRepository,
            LoginEventRepository loginEventRepository,
            UserRepository userRepository,
            AdminAccessPolicy accessPolicy) {
        this.tenantRepository = tenantRepository;
        this.loginEventRepository = loginEventRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
    }

    public TenantMetricsResponse metrics(
            UserRole actorRole, UUID actorTenantId, UUID targetTenantId, Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        Tenant tenant =
                tenantRepository.findById(targetTenantId).orElseThrow(() -> new TenantNotFoundException(targetTenantId));
        if (!accessPolicy.canAccessTenant(actorRole, actorTenantId, tenant.getId())) {
            throw new TenantAccessDeniedException();
        }

        List<LoginEvent> events = loginEventRepository.findByTenantAndOccurredAtBetween(tenant, from, to);
        long successCount =
                events.stream().filter(e -> e.getOutcome() == LoginOutcome.SUCCESS).count();
        long failureCount = events.size() - successCount;
        double errorRate = events.isEmpty() ? 0.0 : (double) failureCount / events.size();
        Map<String, Long> byProvider =
                events.stream().collect(Collectors.groupingBy(LoginEvent::getProvider, Collectors.counting()));
        long activeUsers = events.stream()
                .filter(e -> e.getOutcome() == LoginOutcome.SUCCESS && e.getUser() != null)
                .map(e -> e.getUser().getId())
                .distinct()
                .count();
        long registeredUsers = userRepository.findByTenant(tenant).size();
        double avgLatencyMs = events.isEmpty()
                ? 0.0
                : events.stream().mapToInt(LoginEvent::getLatencyMs).average().orElse(0.0);

        return new TenantMetricsResponse(
                from, to, events.size(), successCount, failureCount, errorRate, byProvider, activeUsers,
                registeredUsers, avgLatencyMs);
    }
}
