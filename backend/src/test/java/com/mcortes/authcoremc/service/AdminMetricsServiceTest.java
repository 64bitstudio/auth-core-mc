package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.LoginEvent;
import com.mcortes.authcoremc.domain.LoginOutcome;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.AdminAccessPolicy;
import com.mcortes.authcoremc.web.TenantAccessDeniedException;
import com.mcortes.authcoremc.web.TenantMetricsResponse;
import com.mcortes.authcoremc.web.TenantNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminMetricsServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private LoginEventRepository loginEventRepository;

    @Mock
    private UserRepository userRepository;

    private final AdminAccessPolicy accessPolicy = new AdminAccessPolicy();

    private AdminMetricsService service() {
        return new AdminMetricsService(tenantRepository, loginEventRepository, userRepository, accessPolicy);
    }

    private Tenant tenantWithId() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private User userWithId(Tenant tenant) {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void aggregatesVolumeByOutcomeProviderAndUsers() {
        Tenant tenant = tenantWithId();
        User user = userWithId(tenant);
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(loginEventRepository.findByTenantAndOccurredAtBetween(tenant, from, to))
                .thenReturn(List.of(
                        new LoginEvent(tenant, user, "PASSWORD", LoginOutcome.SUCCESS, 100),
                        new LoginEvent(tenant, user, "PASSWORD", LoginOutcome.SUCCESS, 200),
                        new LoginEvent(tenant, null, "PASSWORD", LoginOutcome.FAILURE, 50),
                        new LoginEvent(tenant, user, "GOOGLE", LoginOutcome.SUCCESS, 300)));
        when(userRepository.findByTenant(tenant)).thenReturn(List.of(user));

        TenantMetricsResponse result = service().metrics(UserRole.PLATFORM_ADMIN, UUID.randomUUID(), tenant.getId(), from, to);

        assertThat(result.totalLogins()).isEqualTo(4);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.errorRate()).isEqualTo(0.25);
        assertThat(result.byProvider()).containsEntry("PASSWORD", 3L).containsEntry("GOOGLE", 1L);
        assertThat(result.activeUsers()).isEqualTo(1);
        assertThat(result.registeredUsers()).isEqualTo(1);
        assertThat(result.avgLatencyMs()).isEqualTo(162.5);
    }

    @Test
    void aTenantWithNoActivityInRangeReturnsAllZeroMetricsNotAnError() {
        Tenant tenant = tenantWithId();
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(loginEventRepository.findByTenantAndOccurredAtBetween(tenant, from, to)).thenReturn(List.of());
        when(userRepository.findByTenant(tenant)).thenReturn(List.of());

        TenantMetricsResponse result = service().metrics(UserRole.PLATFORM_ADMIN, UUID.randomUUID(), tenant.getId(), from, to);

        assertThat(result.totalLogins()).isZero();
        assertThat(result.errorRate()).isZero();
        assertThat(result.avgLatencyMs()).isZero();
        assertThat(result.byProvider()).isEmpty();
    }

    @Test
    void platformAdminCanQueryAnyTenant() {
        Tenant tenant = tenantWithId();
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(loginEventRepository.findByTenantAndOccurredAtBetween(any(), any(), any())).thenReturn(List.of());
        when(userRepository.findByTenant(tenant)).thenReturn(List.of());

        TenantMetricsResponse result = service().metrics(UserRole.PLATFORM_ADMIN, UUID.randomUUID(), tenant.getId(), from, to);

        assertThat(result).isNotNull();
    }

    @Test
    void tenantAdminCanOnlyQueryItsOwnTenant() {
        Tenant tenant = tenantWithId();
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now();
        UUID tenantId = tenant.getId();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        AdminMetricsService service = service();
        UUID otherActorTenantId = UUID.randomUUID();

        assertThatThrownBy(() -> service.metrics(UserRole.TENANT_ADMIN, otherActorTenantId, tenantId, from, to))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void queryingAnUnknownTenantIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(tenantRepository.findById(unknownId)).thenReturn(Optional.empty());
        AdminMetricsService service = service();
        UUID actorTenantId = UUID.randomUUID();

        assertThatThrownBy(() -> service.metrics(UserRole.PLATFORM_ADMIN, actorTenantId, unknownId, from, to))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void aFromAfterToIsRejected() {
        Instant from = Instant.now();
        Instant to = from.minus(1, ChronoUnit.DAYS);
        AdminMetricsService service = service();
        UUID someTenantId = UUID.randomUUID();
        UUID actorTenantId = UUID.randomUUID();

        assertThatThrownBy(() -> service.metrics(UserRole.PLATFORM_ADMIN, actorTenantId, someTenantId, from, to))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
