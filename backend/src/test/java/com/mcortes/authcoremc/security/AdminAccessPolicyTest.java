package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Ticket 011's acceptance criterion: platform_admin can operate on any
 * tenant, tenant_admin only on its own. Uses real persisted rows (same
 * pattern as {@code TenantIsolationTest}, ticket 008) rather than
 * hand-built objects, so the role column's round-trip through Flyway/JPA
 * is proven too, not just the in-memory decision logic.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class AdminAccessPolicyTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    private final AdminAccessPolicy policy = new AdminAccessPolicy();

    private Tenant newTenant(String name) {
        return tenantRepository.save(new Tenant(name, name + " App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
    }

    private User newUser(Tenant tenant, UserRole role) {
        User user = new User(tenant, "admin@" + tenant.getName().toLowerCase() + ".com", null, "Admin", "User", "hash");
        user.grantRole(role);
        return userRepository.save(user);
    }

    @Test
    void platformAdminCanAccessAnyTenant() {
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        User platformAdmin = newUser(tenantA, UserRole.PLATFORM_ADMIN);

        assertThat(policy.canAccessTenant(platformAdmin, tenantA)).isTrue();
        assertThat(policy.canAccessTenant(platformAdmin, tenantB)).isTrue();
    }

    @Test
    void tenantAdminCanOnlyAccessItsOwnTenant() {
        Tenant tenantA = newTenant("Acme");
        Tenant tenantB = newTenant("Beta");
        User tenantAdmin = newUser(tenantA, UserRole.TENANT_ADMIN);

        assertThat(policy.canAccessTenant(tenantAdmin, tenantA)).isTrue();
        assertThat(policy.canAccessTenant(tenantAdmin, tenantB)).isFalse();
    }

    @Test
    void aUserWithNoAdminRoleCannotAccessAnyTenantAdministratively() {
        Tenant tenantA = newTenant("Acme");
        User regularUser = newUser(tenantA, UserRole.NONE);

        assertThat(policy.canAccessTenant(regularUser, tenantA)).isFalse();
    }

    @Test
    void newUsersDefaultToNoAdminRole() {
        Tenant tenant = newTenant("Acme");
        User user = userRepository.save(new User(tenant, "plain@acme.com", null, "Plain", "User", "hash"));

        assertThat(user.getRole()).isEqualTo(UserRole.NONE);
    }
}
