package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.IdentityClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityClientRepository extends JpaRepository<IdentityClient, UUID> {

    Optional<IdentityClient> findByClientId(String clientId);
}
