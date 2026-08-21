package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantIdentityProviderRepository extends JpaRepository<TenantIdentityProvider, UUID> {

    List<TenantIdentityProvider> findByTenant(Tenant tenant);

    Optional<TenantIdentityProvider> findByTenantAndProvider(Tenant tenant, IdentityProviderType provider);
}
