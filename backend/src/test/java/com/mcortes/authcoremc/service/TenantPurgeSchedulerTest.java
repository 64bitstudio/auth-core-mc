package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import com.mcortes.authcoremc.repository.RefreshTokenRepository;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Ticket 013: proves the {@code @Scheduled} entry point actually filters by
 * the retention window before delegating to {@link TenantPurgeService#purge}.
 * Kept in its own test class because the scheduled loop now lives in its own
 * bean, {@link TenantPurgeScheduler} — see that class's Javadoc for why.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TenantPurgeSchedulerTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private LoginEventRepository loginEventRepository;

    @Autowired
    private TenantIdentityProviderRepository tenantIdentityProviderRepository;

    @Autowired
    private IdentityClientRepository identityClientRepository;

    private TenantPurgeScheduler scheduler() {
        TenantPurgeService purgeService = new TenantPurgeService(
                tenantRepository,
                userRepository,
                refreshTokenRepository,
                loginEventRepository,
                tenantIdentityProviderRepository,
                identityClientRepository);
        return new TenantPurgeScheduler(tenantRepository, purgeService);
    }

    private Tenant deactivatedTenant(int daysAgo) {
        // Unique name per call — tenant.name has a real UNIQUE constraint (ticket 013).
        String name = "Acme-" + UUID.randomUUID();
        Tenant tenant = tenantRepository.save(new Tenant(name, "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        tenant.deactivate();
        ReflectionTestUtils.setField(tenant, "deactivatedAt", Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        return tenantRepository.save(tenant);
    }

    @Test
    void purgeEligibleTenantsOnlyPurgesTheOnesPastTheRetentionWindow() {
        Tenant eligible = deactivatedTenant(91);
        Tenant tooRecent = deactivatedTenant(10);

        scheduler().purgeEligibleTenants();

        assertThat(tenantRepository.findById(eligible.getId())).isEmpty();
        assertThat(tenantRepository.findById(tooRecent.getId())).isPresent();
    }
}
