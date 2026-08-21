package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Tenant persistedTenant() {
        return tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
    }

    @Test
    void savesAUserRegisteredWithOnlyEmail() {
        Tenant tenant = persistedTenant();

        User saved = userRepository.save(new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash"));

        assertThat(userRepository.findByTenantAndEmail(tenant, "ada@example.com"))
                .contains(saved);
    }

    @Test
    void savesAUserRegisteredWithOnlyPhone() {
        Tenant tenant = persistedTenant();

        User saved = userRepository.save(new User(tenant, null, "+525512345678", "Ada", "Lovelace", "hash"));

        assertThat(userRepository.findByTenantAndPhone(tenant, "+525512345678"))
                .contains(saved);
    }

    @Test
    void databaseRejectsAUserWithNeitherEmailNorPhoneEvenBypassingTheJavaConstructor() {
        Tenant tenant = persistedTenant();

        // Bypasses the User() constructor guard on purpose, to prove the CHECK
        // constraint is real defense-in-depth and not just a Java-side check.
        // Asserting only "some RuntimeException" would pass even if the
        // constraint didn't exist (e.g. a typo'd table name also throws) —
        // so we require the specific constraint name in the error instead.
        assertThatThrownBy(() -> {
                    entityManager
                            .createNativeQuery(
                                    "INSERT INTO app_user "
                                            + "(id, tenant_id, email, phone, nombre, apellidos, email_verified, phone_verified, created_at) "
                                            + "VALUES (?1, ?2, NULL, NULL, 'Ada', 'Lovelace', false, false, now())")
                            .setParameter(1, UUID.randomUUID())
                            .setParameter(2, tenant.getId())
                            .executeUpdate();
                    entityManager.flush();
                })
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("app_user_email_or_phone_required");
    }

    @Test
    void doesNotAllowTwoUsersWithTheSameEmailInTheSameTenant() {
        Tenant tenant = persistedTenant();
        userRepository.save(new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash"));
        entityManager.flush();

        // The insert is deferred by Hibernate until this explicit flush, and
        // since the flush call itself (not userRepository.save()) is what
        // throws, it bypasses Spring's @Repository exception translation —
        // same reasoning as the CHECK-constraint test above.
        assertThatThrownBy(() -> {
                    userRepository.save(new User(tenant, "ada@example.com", null, "Ada2", "Lovelace2", "hash2"));
                    entityManager.flush();
                })
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("app_user_tenant_email_unique");
    }

    @Test
    void allowsTheSameEmailAcrossDifferentTenants() {
        Tenant tenantA = persistedTenant();
        Tenant tenantB = tenantRepository.save(
                new Tenant("Beta", "Beta App", "#FF0057", 900, 2_592_000, 86_400, 3_600, 300));

        userRepository.save(new User(tenantA, "shared@example.com", null, "Ada", "Lovelace", "hash"));
        userRepository.save(new User(tenantB, "shared@example.com", null, "Grace", "Hopper", "hash"));
        entityManager.flush();

        assertThat(userRepository.findByTenantAndEmail(tenantA, "shared@example.com")).isPresent();
        assertThat(userRepository.findByTenantAndEmail(tenantB, "shared@example.com")).isPresent();
    }
}
