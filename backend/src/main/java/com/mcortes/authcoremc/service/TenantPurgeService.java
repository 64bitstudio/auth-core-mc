package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import com.mcortes.authcoremc.repository.RefreshTokenRepository;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 013: physically purges a tenant's data 90 days after it was
 * deactivated (see {@code Tenant#deactivate}) — decision confirmed with
 * the Product Owner during the definition phase (see
 * docs/definiciones/panel-administracion-clientes.md).
 *
 * <p>Deletes explicitly, in FK-dependency order, rather than relying on
 * DB-level {@code ON DELETE CASCADE} — the schema doesn't have cascade
 * configured anywhere else in this codebase, and an explicit order here is
 * easier to audit than a cascade that could reach further than intended:
 * refresh_token (via its users) → login_event → tenant_identity_provider →
 * identity_client → app_user → tenant itself.
 *
 * <p>The {@code @Scheduled} entry point lives in {@link TenantPurgeScheduler},
 * a separate bean — see that class's Javadoc for why (real SonarQube finding,
 * java:S2229 BLOCKER: a same-class {@code this.purge(...)} self-invocation
 * would have silently skipped {@code @Transactional}).
 */
@Service
public class TenantPurgeService {

    private static final Logger LOG = LoggerFactory.getLogger(TenantPurgeService.class);
    static final int RETENTION_DAYS = 90;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginEventRepository loginEventRepository;
    private final TenantIdentityProviderRepository tenantIdentityProviderRepository;
    private final IdentityClientRepository identityClientRepository;

    public TenantPurgeService(
            TenantRepository tenantRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            LoginEventRepository loginEventRepository,
            TenantIdentityProviderRepository tenantIdentityProviderRepository,
            IdentityClientRepository identityClientRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginEventRepository = loginEventRepository;
        this.tenantIdentityProviderRepository = tenantIdentityProviderRepository;
        this.identityClientRepository = identityClientRepository;
    }

    /** Whether a tenant has been deactivated long enough to be purged — shared by {@link #purge} and {@link TenantPurgeScheduler}. */
    public boolean isEligibleForPurge(Tenant tenant) {
        return tenant.getDeactivatedAt() != null
                && tenant.getDeactivatedAt().isBefore(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
    }

    @Transactional
    public void purge(Tenant tenant) {
        if (!isEligibleForPurge(tenant)) {
            throw new IllegalStateException(
                    "Refusing to purge tenant " + tenant.getId() + " — not deactivated for at least "
                            + RETENTION_DAYS + " days");
        }

        List<User> users = userRepository.findByTenant(tenant);
        refreshTokenRepository.deleteAll(refreshTokenRepository.findByUserIn(users));
        loginEventRepository.deleteAll(loginEventRepository.findByTenant(tenant));
        tenantIdentityProviderRepository.deleteAll(tenantIdentityProviderRepository.findByTenant(tenant));
        identityClientRepository.deleteAll(identityClientRepository.findByTenant(tenant));
        userRepository.deleteAll(users);
        tenantRepository.delete(tenant);

        LOG.info("Purged tenant {} (deactivated {}), including {} users", tenant.getId(), tenant.getDeactivatedAt(), users.size());
    }
}
