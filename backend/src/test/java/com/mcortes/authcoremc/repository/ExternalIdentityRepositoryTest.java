package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ExternalIdentityRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExternalIdentityRepository externalIdentityRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Tenant persistedTenant() {
        return tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
    }

    private User persistedUser(Tenant tenant, String email) {
        return userRepository.save(new User(tenant, email, null, "Ada", "Lovelace", "hash"));
    }

    @Test
    void savesAndFindsALinkedExternalIdentity() {
        Tenant tenant = persistedTenant();
        User user = persistedUser(tenant, "ada@example.com");

        ExternalIdentity saved = externalIdentityRepository.save(
                new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-123"));

        assertThat(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenant, IdentityProviderType.GOOGLE, "google-sub-123"))
                .contains(saved);
        assertThat(externalIdentityRepository.findByUser(user)).containsExactly(saved);
    }

    @Test
    void aUserCanLinkMoreThanOneDifferentProvider() {
        Tenant tenant = persistedTenant();
        User user = persistedUser(tenant, "ada@example.com");

        externalIdentityRepository.save(
                new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-123"));
        externalIdentityRepository.save(
                new ExternalIdentity(tenant, user, IdentityProviderType.FACEBOOK, "facebook-id-456"));
        entityManager.flush();

        assertThat(externalIdentityRepository.findByUser(user)).hasSize(2);
    }

    @Test
    void doesNotAllowTheSameProviderAccountToBeLinkedTwiceWithinTheSameTenant() {
        Tenant tenant = persistedTenant();
        User userA = persistedUser(tenant, "ada@example.com");
        User userB = persistedUser(tenant, "grace@example.com");

        externalIdentityRepository.save(
                new ExternalIdentity(tenant, userA, IdentityProviderType.GOOGLE, "google-sub-123"));
        entityManager.flush();

        // The insert is deferred by Hibernate until this explicit flush, and
        // since the flush call itself (not the repository's save()) is what
        // throws, it bypasses Spring's @Repository exception translation —
        // same reasoning as UserRepositoryTest's unique-constraint tests.
        // save() is called outside the assertion on purpose (Sonar java:S5778):
        // only the one call actually expected to throw — flush() — belongs
        // inside assertThatThrownBy, so the assertion stays unambiguous about
        // what it's testing.
        externalIdentityRepository.save(
                new ExternalIdentity(tenant, userB, IdentityProviderType.GOOGLE, "google-sub-123"));
        assertThatThrownBy(entityManager::flush)
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("external_identity_tenant_provider_unique");
    }

    @Test
    void doesNotAllowAUserToLinkTheSameProviderTwice() {
        Tenant tenant = persistedTenant();
        User user = persistedUser(tenant, "ada@example.com");

        externalIdentityRepository.save(
                new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-123"));
        entityManager.flush();

        externalIdentityRepository.save(
                new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-999"));
        assertThatThrownBy(entityManager::flush)
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("external_identity_user_provider_unique");
    }

    @Test
    void theSameProviderAccountCanBeLinkedInDifferentTenants() {
        Tenant tenantA = persistedTenant();
        Tenant tenantB = tenantRepository.save(
                new Tenant("Beta", "Beta App", "#FF0057", 900, 2_592_000, 86_400, 3_600, 300));
        User userA = persistedUser(tenantA, "ada@example.com");
        User userB = persistedUser(tenantB, "ada@beta.example.com");

        externalIdentityRepository.save(
                new ExternalIdentity(tenantA, userA, IdentityProviderType.GOOGLE, "google-sub-shared"));
        externalIdentityRepository.save(
                new ExternalIdentity(tenantB, userB, IdentityProviderType.GOOGLE, "google-sub-shared"));
        entityManager.flush();

        assertThat(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenantA, IdentityProviderType.GOOGLE, "google-sub-shared"))
                .isPresent();
        assertThat(externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                        tenantB, IdentityProviderType.GOOGLE, "google-sub-shared"))
                .isPresent();
    }
}
