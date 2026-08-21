package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.service.TenantIdentityProviderService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configures which social providers a tenant has enabled. Scoped to
 * whichever tenant {@code X-Client-Id} resolves to (same header as every
 * other endpoint in this API) — there's no separate tenantId path
 * parameter, so a caller can never target a tenant other than its own.
 */
@RestController
@RequestMapping("/api/v1/identity-providers")
public class IdentityProviderController {

    private final ClientContextResolver clientContextResolver;
    private final TenantIdentityProviderService providerService;

    public IdentityProviderController(
            ClientContextResolver clientContextResolver, TenantIdentityProviderService providerService) {
        this.clientContextResolver = clientContextResolver;
        this.providerService = providerService;
    }

    @GetMapping
    public ResponseEntity<List<IdentityProviderView>> list(@RequestHeader("X-Client-Id") String clientId) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        List<IdentityProviderView> views =
                providerService.list(tenant).stream().map(IdentityProviderView::from).toList();
        return ResponseEntity.ok(views);
    }

    @PutMapping("/{provider}")
    public ResponseEntity<IdentityProviderView> configure(
            @RequestHeader("X-Client-Id") String clientId,
            @PathVariable IdentityProviderType provider,
            @Valid @RequestBody ConfigureIdentityProviderRequest request) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        var entity = providerService.configure(tenant, provider, request.clientId(), request.clientSecret());
        return ResponseEntity.ok(IdentityProviderView.from(entity));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disable(
            @RequestHeader("X-Client-Id") String clientId, @PathVariable IdentityProviderType provider) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        providerService.disable(tenant, provider);
        return ResponseEntity.noContent().build();
    }
}
