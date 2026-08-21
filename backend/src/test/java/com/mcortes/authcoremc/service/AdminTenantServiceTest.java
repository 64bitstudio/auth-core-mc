package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.security.AdminAccessPolicy;
import com.mcortes.authcoremc.web.CreateTenantRequest;
import com.mcortes.authcoremc.web.DuplicateTenantNameException;
import com.mcortes.authcoremc.web.TenantAccessDeniedException;
import com.mcortes.authcoremc.web.TenantNotFoundException;
import com.mcortes.authcoremc.web.UpdateTenantRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminTenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    private final AdminAccessPolicy accessPolicy = new AdminAccessPolicy();

    private AdminTenantService service() {
        return new AdminTenantService(tenantRepository, accessPolicy);
    }

    private Tenant tenantWithId() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    @Test
    void platformAdminCanCreateATenant() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.empty());
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreateTenantRequest request = new CreateTenantRequest("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

        Tenant result = service().create(UserRole.PLATFORM_ADMIN, request);

        assertThat(result.getName()).isEqualTo("Acme");
    }

    @Test
    void aTenantAdminCannotCreateATenant() {
        CreateTenantRequest request = new CreateTenantRequest("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        AdminTenantService service = service();

        assertThatThrownBy(() -> service.create(UserRole.TENANT_ADMIN, request))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void creatingWithADuplicateNameIsRejected() {
        when(tenantRepository.findByName("Acme")).thenReturn(Optional.of(tenantWithId()));
        CreateTenantRequest request = new CreateTenantRequest("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        AdminTenantService service = service();

        assertThatThrownBy(() -> service.create(UserRole.PLATFORM_ADMIN, request))
                .isInstanceOf(DuplicateTenantNameException.class);
    }

    @Test
    void platformAdminCanGetAnyTenant() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));

        Tenant result = service().get(UserRole.PLATFORM_ADMIN, UUID.randomUUID(), tenant.getId());

        assertThat(result).isEqualTo(tenant);
    }

    @Test
    void tenantAdminCanOnlyGetItsOwnTenant() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        AdminTenantService service = service();

        assertThatThrownBy(() -> service.get(UserRole.TENANT_ADMIN, UUID.randomUUID(), tenant.getId()))
                .isInstanceOf(TenantAccessDeniedException.class);

        Tenant result = service.get(UserRole.TENANT_ADMIN, tenant.getId(), tenant.getId());
        assertThat(result).isEqualTo(tenant);
    }

    @Test
    void gettingAnUnknownTenantIdIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(tenantRepository.findById(unknownId)).thenReturn(Optional.empty());
        AdminTenantService service = service();

        assertThatThrownBy(() -> service.get(UserRole.PLATFORM_ADMIN, UUID.randomUUID(), unknownId))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void updatingATenantChangesItsEditableFieldsButNotItsName() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UpdateTenantRequest request = new UpdateTenantRequest("New App Name", "#FF0000", 1, 1, 1, 1, 1);

        Tenant result = service().update(UserRole.PLATFORM_ADMIN, UUID.randomUUID(), tenant.getId(), request);

        assertThat(result.getName()).isEqualTo("Acme");
        assertThat(result.getAppName()).isEqualTo("New App Name");
        assertThat(result.getPrimaryColor()).isEqualTo("#FF0000");
    }

    @Test
    void onlyPlatformAdminCanDeactivateATenant() {
        Tenant tenant = tenantWithId();
        AdminTenantService service = service();

        assertThatThrownBy(() -> service.deactivate(UserRole.TENANT_ADMIN, tenant.getId()))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void deactivatingSetsDeactivatedAt() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().deactivate(UserRole.PLATFORM_ADMIN, tenant.getId());

        assertThat(tenant.isActive()).isFalse();
        assertThat(tenant.getDeactivatedAt()).isNotNull();
    }
}
