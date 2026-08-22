package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    /** Resolves an inbound social login to the app_user it belongs to (ticket 037+). */
    Optional<ExternalIdentity> findByTenantAndProviderAndProviderUserId(
            Tenant tenant, IdentityProviderType provider, String providerUserId);

    /** Lists a user's linked providers, e.g. for /ui/cuenta. */
    List<ExternalIdentity> findByUser(User user);
}
