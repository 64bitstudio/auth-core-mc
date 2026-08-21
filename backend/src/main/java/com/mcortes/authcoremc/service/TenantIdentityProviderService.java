package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TenantIdentityProvider;
import com.mcortes.authcoremc.repository.TenantIdentityProviderRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.security.TenantSecretEncryptor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant social login configuration. {@code client_secret} is
 * encrypted at rest via envelope encryption ({@link TenantSecretEncryptor},
 * ticket 017 — each tenant has its own Vault-wrapped data-key, generated
 * lazily on first use here) and this service NEVER returns it in
 * decrypted form to a caller — see
 * {@link com.mcortes.authcoremc.web.IdentityProviderView}, which simply
 * omits it.
 *
 * <p>Apple is deliberately rejected here (see {@link UnsupportedProviderException}):
 * it doesn't use a client_id/client_secret pair like Google/Facebook — it
 * needs a Sign in with Apple private key, Team ID and Key ID from a paid
 * Apple Developer Program membership, which docs/README.md notes is still
 * pending the Product Owner's confirmation.
 */
@Service
public class TenantIdentityProviderService {

    private final TenantIdentityProviderRepository repository;
    private final TenantRepository tenantRepository;
    private final TenantSecretEncryptor secretEncryptor;

    public TenantIdentityProviderService(
            TenantIdentityProviderRepository repository,
            TenantRepository tenantRepository,
            TenantSecretEncryptor secretEncryptor) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
        this.secretEncryptor = secretEncryptor;
    }

    public List<TenantIdentityProvider> list(Tenant tenant) {
        return repository.findByTenant(tenant);
    }

    @Transactional
    public TenantIdentityProvider configure(
            Tenant tenant, IdentityProviderType provider, String clientId, String rawClientSecret) {
        if (provider == IdentityProviderType.APPLE) {
            throw new UnsupportedProviderException(
                    "Apple Sign In is not available yet — it requires a paid Apple Developer Program "
                            + "membership, pending Product Owner confirmation. See docs/README.md.");
        }
        if (clientId == null || clientId.isBlank() || rawClientSecret == null || rawClientSecret.isBlank()) {
            throw new IllegalArgumentException("clientId and clientSecret are required");
        }

        String wrappedDataKeyBefore = tenant.getWrappedDataKey();
        String wrappedDataKey = secretEncryptor.ensureWrappedDataKey(tenant);
        if (wrappedDataKeyBefore == null) {
            tenantRepository.save(tenant);
        }

        TenantIdentityProvider entry = repository
                .findByTenantAndProvider(tenant, provider)
                .orElseGet(() -> new TenantIdentityProvider(tenant, provider));
        entry.configure(clientId, secretEncryptor.encrypt(wrappedDataKey, rawClientSecret));
        return repository.save(entry);
    }

    @Transactional
    public void disable(Tenant tenant, IdentityProviderType provider) {
        repository.findByTenantAndProvider(tenant, provider).ifPresent(entry -> {
            entry.disable();
            repository.save(entry);
        });
    }
}
