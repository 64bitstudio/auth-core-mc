package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantAndEmail(Tenant tenant, String email);

    Optional<User> findByTenantAndPhone(Tenant tenant, String phone);
}
