package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.UserRole;
import com.mcortes.authcoremc.service.AdminMetricsService;
import com.mcortes.authcoremc.service.AdminTenantService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 013: tenant CRUD for the admin panel. Coarse role gate
 * ({@code /api/v1/admin/**} requires TENANT_ADMIN or PLATFORM_ADMIN) lives
 * in {@code SecurityConfig} (ticket 012); the fine-grained "which specific
 * tenant" check lives in {@code AdminTenantService}, reading role/tenant_id
 * straight off the JWT claims (see {@code AdminClaimsCustomizer}) — no DB
 * lookup of the caller needed for authorization.
 *
 * <p>Ticket 016's {@code /metrics} endpoint lives here too (not a separate
 * controller) — it shares the exact same {@code /api/v1/admin/tenants/{id}}
 * URL space and role/tenant-claim extraction helpers.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class AdminTenantController {

    private static final int DEFAULT_RANGE_DAYS = 30;

    private final AdminTenantService service;
    private final AdminMetricsService metricsService;

    public AdminTenantController(AdminTenantService service, AdminMetricsService metricsService) {
        this.service = service;
        this.metricsService = metricsService;
    }

    /** Ticket 019: platform_admin only — see {@code AdminTenantService.list} for why this isn't delegated to the per-tenant access policy. */
    @GetMapping
    public ResponseEntity<List<TenantResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        List<TenantResponse> tenants =
                service.list(role(jwt)).stream().map(TenantResponse::from).toList();
        return ResponseEntity.ok(tenants);
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = service.create(role(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.from(tenant));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Tenant tenant = service.get(role(jwt), tenantId(jwt), id);
        return ResponseEntity.ok(TenantResponse.from(tenant));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantResponse> update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest request) {
        Tenant tenant = service.update(role(jwt), tenantId(jwt), id, request);
        return ResponseEntity.ok(TenantResponse.from(tenant));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deactivate(role(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /** Ticket 022: {@code Tenant.reactivate()} has existed since ticket 013 — this is the first endpoint to expose it. */
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.reactivate(role(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Ticket 016: {@code from}/{@code to} default to the last 30 days when
     * omitted — a first visit to the metrics page shouldn't require typing
     * a date range just to see something. A tenant with no activity in the
     * range is a normal 200 with all-zero fields, not an error (see {@code
     * AdminMetricsService}).
     */
    @GetMapping("/{id}/metrics")
    public ResponseEntity<TenantMetricsResponse> metrics(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_RANGE_DAYS, ChronoUnit.DAYS);
        TenantMetricsResponse response =
                metricsService.metrics(role(jwt), tenantId(jwt), id, effectiveFrom, effectiveTo);
        return ResponseEntity.ok(response);
    }

    private UserRole role(Jwt jwt) {
        String claim = jwt.getClaimAsString("role");
        return claim == null ? UserRole.NONE : UserRole.valueOf(claim);
    }

    private UUID tenantId(Jwt jwt) {
        String claim = jwt.getClaimAsString("tenant_id");
        return claim == null ? null : UUID.fromString(claim);
    }
}
