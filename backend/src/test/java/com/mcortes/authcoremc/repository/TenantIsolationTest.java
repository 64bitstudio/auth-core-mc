package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Ticket 008's central acceptance criterion: "todo dato de negocio está
 * particionado por tenant_id (ninguna consulta cruza tenants por
 * accidente)". This class is the one place that proves that property
 * holistically, across every repository — instead of trusting that each
 * repository test file happens to cover it incidentally.
 *
 * <p>Two repositories deliberately expose a lookup that is NOT scoped by
 * tenant ({@link IdentityClientRepository#findByClientId} and
 * {@link RefreshTokenRepository#findByTokenHash}) — this is not an
 * oversight, so this class also proves (not just asserts in a comment)
 * that both are still safe: {@code client_id} is itself the mechanism
 * that resolves which tenant a request belongs to (see
 * {@code ClientContextResolver}), and a {@code token_hash} is a SHA-256
 * digest of a high-entropy random value, so scoping its lookup by tenant
 * would add no real isolation — possession of the raw token is already
 * the only thing that lets a caller reach a given row. See
 * docs/ARQUITECTURA.md ticket 008 for the full rationale.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TenantIsolationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantIdentityProviderRepository providerRepository;

    @Autowired
    private IdentityClientRepository clientRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private Tenant newTenant(String name) {
        return tenantRepository.save(new Tenant(name, name + " App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
    }

    @Test
    void findByTenantAndEmailNeverReturnsAnotherTenantsUserEvenWithTheSameEmail() {
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        User userA = userRepository.save(new User(tenantA, "shared@example.com", null, "Ada", "Lovelace", "hash"));
        User userB = userRepository.save(new User(tenantB, "shared@example.com", null, "Grace", "Hopper", "hash"));

        User foundForA = userRepository.findByTenantAndEmail(tenantA, "shared@example.com").orElseThrow();
        User foundForB = userRepository.findByTenantAndEmail(tenantB, "shared@example.com").orElseThrow();

        assertThat(foundForA.getId()).isEqualTo(userA.getId());
        assertThat(foundForB.getId()).isEqualTo(userB.getId());
        assertThat(foundForA.getId()).isNotEqualTo(foundForB.getId());
    }

    @Test
    void findByTenantAndPhoneNeverReturnsAnotherTenantsUserEvenWithTheSamePhone() {
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        User userA = userRepository.save(new User(tenantA, null, "+525511112222", "Ada", "Lovelace", "hash"));
        User userB = userRepository.save(new User(tenantB, null, "+525511112222", "Grace", "Hopper", "hash"));

        User foundForA = userRepository.findByTenantAndPhone(tenantA, "+525511112222").orElseThrow();
        User foundForB = userRepository.findByTenantAndPhone(tenantB, "+525511112222").orElseThrow();

        assertThat(foundForA.getId()).isEqualTo(userA.getId());
        assertThat(foundForB.getId()).isEqualTo(userB.getId());
    }

    @Test
    void findByTenantAndProviderNeverReturnsAnotherTenantsSocialLoginConfig() {
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        TenantIdentityProvider providerA = new TenantIdentityProvider(tenantA, IdentityProviderType.GOOGLE);
        providerA.configure("acme-google-client-id", "acme-encrypted-secret");
        TenantIdentityProvider providerB = new TenantIdentityProvider(tenantB, IdentityProviderType.GOOGLE);
        providerB.configure("beta-google-client-id", "beta-encrypted-secret");
        providerRepository.save(providerA);
        providerRepository.save(providerB);

        TenantIdentityProvider foundForA =
                providerRepository.findByTenantAndProvider(tenantA, IdentityProviderType.GOOGLE).orElseThrow();

        assertThat(foundForA.getClientId()).isEqualTo("acme-google-client-id");
        assertThat(foundForA.getClientId()).isNotEqualTo(providerB.getClientId());
    }

    @Test
    void aClientIdAlwaysResolvesBackToItsOwnTenantEvenThoughTheLookupItselfIsNotTenantScoped() {
        // findByClientId isn't (and can't be) filtered by tenant — it IS the
        // mechanism that tells the caller which tenant a request belongs to
        // (see ClientContextResolver). What matters for isolation is that the
        // client this returns always carries the correct tenant, never a
        // different one — proven here rather than just asserted in prose.
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        clientRepository.save(new IdentityClient(
                tenantA, "acme-app", null, true, List.of("https://acme.example.com/callback")));
        clientRepository.save(new IdentityClient(
                tenantB, "beta-app", null, true, List.of("https://beta.example.com/callback")));

        IdentityClient foundA = clientRepository.findByClientId("acme-app").orElseThrow();
        IdentityClient foundB = clientRepository.findByClientId("beta-app").orElseThrow();

        assertThat(foundA.getTenant().getId()).isEqualTo(tenantA.getId());
        assertThat(foundB.getTenant().getId()).isEqualTo(tenantB.getId());
    }

    @Test
    void aRefreshTokenAlwaysResolvesBackToTheUserAndClientOfTheSameTenantEvenThoughTheHashLookupIsGlobal() {
        // Same reasoning as above: token_hash isn't tenant-scoped because a
        // SHA-256 digest of a high-entropy token is already unguessable —
        // adding a tenant filter on top would add no real protection. What
        // has to hold is that the row this returns never mixes users/clients
        // from different tenants.
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        User userA = userRepository.save(new User(tenantA, "ada@example.com", null, "Ada", "Lovelace", "hash"));
        User userB = userRepository.save(new User(tenantB, "grace@example.com", null, "Grace", "Hopper", "hash"));
        IdentityClient clientA = clientRepository.save(
                new IdentityClient(tenantA, "acme-app", null, true, List.of("https://acme.example.com/callback")));
        IdentityClient clientB = clientRepository.save(
                new IdentityClient(tenantB, "beta-app", null, true, List.of("https://beta.example.com/callback")));
        refreshTokenRepository.save(
                new RefreshToken(userA, clientA, "hash-for-tenant-a", Instant.now().plus(30, ChronoUnit.DAYS)));
        refreshTokenRepository.save(
                new RefreshToken(userB, clientB, "hash-for-tenant-b", Instant.now().plus(30, ChronoUnit.DAYS)));

        RefreshToken foundA = refreshTokenRepository.findByTokenHash("hash-for-tenant-a").orElseThrow();

        assertThat(foundA.getUser().getTenant().getId()).isEqualTo(tenantA.getId());
        assertThat(foundA.getClient().getTenant().getId()).isEqualTo(tenantA.getId());
    }
}
