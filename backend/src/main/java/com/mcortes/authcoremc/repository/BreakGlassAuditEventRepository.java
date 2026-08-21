package com.mcortes.authcoremc.repository;

import com.mcortes.authcoremc.domain.BreakGlassAuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakGlassAuditEventRepository extends JpaRepository<BreakGlassAuditEvent, UUID> {}
