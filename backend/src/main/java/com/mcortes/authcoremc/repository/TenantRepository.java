package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.Tenant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {}
