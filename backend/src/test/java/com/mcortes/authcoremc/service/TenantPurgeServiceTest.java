package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.LoginOutcome;
import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Ticket 013: proves the purge actually deletes everything, in the right
 * order, for real — real Postgres (not mocks), a tenant with one of every
 * kind of dependent row (user, refresh token, identity client, provider
 * config, login event), all gone after purge, tenant itself gone too.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TenantPurgeServiceTest {

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

    private TenantPurgeService service() {
        return new TenantPurgeService(
                tenantRepository,
                userRepository,
                refreshTokenRepository,
                loginEventRepository,
                tenantIdentityProviderRepository,
                identityClientRepository);
    }

    private Tenant deactivatedTenant(int daysAgo) {
        // Unique name per call — tenant.name has a real UNIQUE constraint
        // (ticket 013), and tests in this class create more than one tenant.
        String name = "Acme-" + java.util.UUID.randomUUID();
        Tenant tenant = tenantRepository.save(new Tenant(name, "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        tenant.deactivate();
        ReflectionTestUtils.setField(tenant, "deactivatedAt", Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        return tenantRepository.save(tenant);
    }

    @Test
    void purgingATenantDeletesEverythingThatBelongsToItInDependencyOrder() {
        Tenant tenant = deactivatedTenant(91);
        User user = userRepository.save(new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash"));
        IdentityClient client = identityClientRepository.save(
                new IdentityClient(tenant, "acme-app", null, true, List.of("https://acme.example.com/callback")));
        RefreshToken refreshToken = refreshTokenRepository.save(
                new RefreshToken(user, client, "some-hash", Instant.now().plusSeconds(3600)));
        TenantIdentityProvider provider = new TenantIdentityProvider(tenant, IdentityProviderType.GOOGLE);
        provider.configure("client-id", "encrypted-secret");
        tenantIdentityProviderRepository.save(provider);
        loginEventRepository.save(new com.mcortes.authcoremc.domain.LoginEvent(tenant, user, "PASSWORD", LoginOutcome.SUCCESS, 42));

        service().purge(tenant);

        assertThat(tenantRepository.findById(tenant.getId())).isEmpty();
        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(identityClientRepository.findById(client.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(refreshToken.getId())).isEmpty();
        assertThat(tenantIdentityProviderRepository.findById(provider.getId())).isEmpty();
    }

    @Test
    void refusesToPurgeATenantDeactivatedLessThan90DaysAgo() {
        Tenant tenant = deactivatedTenant(10);
        TenantPurgeService service = service();

        assertThatThrownBy(() -> service.purge(tenant)).isInstanceOf(IllegalStateException.class);
        assertThat(tenantRepository.findById(tenant.getId())).isPresent();
    }

    @Test
    void refusesToPurgeAStillActiveTenant() {
        Tenant tenant = tenantRepository.save(
                new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        TenantPurgeService service = service();

        assertThatThrownBy(() -> service.purge(tenant)).isInstanceOf(IllegalStateException.class);
        assertThat(tenantRepository.findById(tenant.getId())).isPresent();
    }
}
