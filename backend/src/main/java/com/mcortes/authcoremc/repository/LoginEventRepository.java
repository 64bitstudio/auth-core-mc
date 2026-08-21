package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.LoginEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {}
