package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.security.AdminAccessPolicy;
import com.mcortes.authcoremc.web.CreateTenantRequest;
import com.mcortes.authcoremc.web.DuplicateTenantNameException;
import com.mcortes.authcoremc.web.TenantAccessDeniedException;
import com.mcortes.authcoremc.web.TenantNotFoundException;
import com.mcortes.authcoremc.web.UpdateTenantRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 013: tenant CRUD for the admin panel. Fine-grained per-tenant
 * access ({@link AdminAccessPolicy}) is enforced here, off the JWT's
 * role/tenant_id claims — the coarse "has SOME admin role" check already
 * happened in {@code SecurityConfig}'s {@code /api/v1/admin/**} rule
 * (ticket 012); this is the "which specific tenant" check that rule can't
 * express.
 *
 * <p>Create/deactivate are platform_admin-only (see HU-1/HU-5 in
 * docs/definiciones/panel-administracion-clientes.md) — a tenant_admin
 * doesn't get to create sibling tenants or deactivate its own.
 */
@Service
public class AdminTenantService {

    private final TenantRepository tenantRepository;
    private final AdminAccessPolicy accessPolicy;

    public AdminTenantService(TenantRepository tenantRepository, AdminAccessPolicy accessPolicy) {
        this.tenantRepository = tenantRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public Tenant create(UserRole actorRole, CreateTenantRequest request) {
        requirePlatformAdmin(actorRole);
        if (tenantRepository.findByName(request.name()).isPresent()) {
            throw new DuplicateTenantNameException(request.name());
        }
        Tenant tenant = new Tenant(
                request.name(),
                request.appName(),
                request.primaryColor(),
                request.accessTokenTtlSeconds(),
                request.refreshTokenTtlSeconds(),
                request.emailVerificationTtlSeconds(),
                request.passwordResetTtlSeconds(),
                request.otpTtlSeconds());
        return tenantRepository.save(tenant);
    }

    public Tenant get(UserRole actorRole, UUID actorTenantId, UUID targetTenantId) {
        Tenant tenant = findOrThrow(targetTenantId);
        requireAccess(actorRole, actorTenantId, tenant.getId());
        return tenant;
    }

    @Transactional
    public Tenant update(UserRole actorRole, UUID actorTenantId, UUID targetTenantId, UpdateTenantRequest request) {
        Tenant tenant = findOrThrow(targetTenantId);
        requireAccess(actorRole, actorTenantId, tenant.getId());
        tenant.update(
                request.appName(),
                request.primaryColor(),
                request.accessTokenTtlSeconds(),
                request.refreshTokenTtlSeconds(),
                request.emailVerificationTtlSeconds(),
                request.passwordResetTtlSeconds(),
                request.otpTtlSeconds());
        return tenantRepository.save(tenant);
    }

    @Transactional
    public void deactivate(UserRole actorRole, UUID targetTenantId) {
        requirePlatformAdmin(actorRole);
        Tenant tenant = findOrThrow(targetTenantId);
        tenant.deactivate();
        tenantRepository.save(tenant);
    }

    private Tenant findOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    private void requirePlatformAdmin(UserRole role) {
        if (role != UserRole.PLATFORM_ADMIN) {
            throw new TenantAccessDeniedException();
        }
    }

    private void requireAccess(UserRole role, UUID actorTenantId, UUID targetTenantId) {
        if (!accessPolicy.canAccessTenant(role, actorTenantId, targetTenantId)) {
            throw new TenantAccessDeniedException();
        }
    }
}
