package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.service.TenantIdentityProviderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 014: admin-panel counterpart to {@link IdentityProviderController}
 * (ticket 006) — same {@link TenantIdentityProviderService}, untouched;
 * only how the caller and tenant are resolved differs. That endpoint is
 * reached via the {@code X-Client-Id} header (the client app's own
 * dogfooding path, no auth); this one is reached via a real admin Bearer
 * JWT. Mapped under {@code /api/v1/admin/**}, so {@code SecurityConfig}'s
 * existing role rule (ticket 012) already gates it — no security config
 * change needed for this ticket.
 *
 * <p>Always operates on the caller's OWN tenant, read from the JWT's
 * {@code tenant_id} claim (see {@code AdminClaimsCustomizer}) — a
 * platform_admin managing a tenant that isn't their own would need a
 * tenant selector, which is out of scope here (see docs/ARQUITECTURA.md,
 * ticket 014, for the full rationale).
 */
@RestController
@RequestMapping("/api/v1/admin/identity-providers")
public class AdminIdentityProviderController {

    private final TenantRepository tenantRepository;
    private final TenantIdentityProviderService providerService;

    public AdminIdentityProviderController(TenantRepository tenantRepository, TenantIdentityProviderService providerService) {
        this.tenantRepository = tenantRepository;
        this.providerService = providerService;
    }

    @GetMapping
    public ResponseEntity<List<IdentityProviderView>> list(@AuthenticationPrincipal Jwt jwt) {
        Tenant tenant = ownTenant(jwt);
        List<IdentityProviderView> views =
                providerService.list(tenant).stream().map(IdentityProviderView::from).toList();
        return ResponseEntity.ok(views);
    }

    @PutMapping("/{provider}")
    public ResponseEntity<IdentityProviderView> configure(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable IdentityProviderType provider,
            @Valid @RequestBody ConfigureIdentityProviderRequest request) {
        Tenant tenant = ownTenant(jwt);
        var entity = providerService.configure(tenant, provider, request.clientId(), request.clientSecret());
        return ResponseEntity.ok(IdentityProviderView.from(entity));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal Jwt jwt, @PathVariable IdentityProviderType provider) {
        Tenant tenant = ownTenant(jwt);
        providerService.disable(tenant, provider);
        return ResponseEntity.noContent().build();
    }

    private Tenant ownTenant(Jwt jwt) {
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        return tenantRepository.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    }
}
