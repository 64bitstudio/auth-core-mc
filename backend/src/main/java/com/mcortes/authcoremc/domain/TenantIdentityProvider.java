package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Per-tenant configuration for a social login provider (ticket 006).
 *
 * <p>{@code clientSecretEncrypted} holds a reversibly-encrypted secret, not a
 * hash — unlike a user password, this value must be readable in clear text
 * again to authenticate outgoing calls to Google/Facebook/Apple. This is a
 * deliberate exception to the "standard encryption" (hash + disk + TLS)
 * chosen for regular PII — see docs/ARQUITECTURA.md, decision 6. The actual
 * encryption/decryption is out of scope for this ticket (001); it lands with
 * ticket 006.
 */
@Entity
@Table(name = "tenant_identity_provider")
public class TenantIdentityProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdentityProviderType provider;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "client_secret_encrypted")
    private String clientSecretEncrypted;

    protected TenantIdentityProvider() {
        // JPA
    }

    public TenantIdentityProvider(Tenant tenant, IdentityProviderType provider) {
        this.tenant = tenant;
        this.provider = provider;
        this.enabled = false;
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public IdentityProviderType getProvider() {
        return provider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretEncrypted() {
        return clientSecretEncrypted;
    }

    /** Enables this provider with the given credentials (ticket 006 owns validating them). */
    public void configure(String clientId, String clientSecretEncrypted) {
        if (clientId == null || clientId.isBlank() || clientSecretEncrypted == null || clientSecretEncrypted.isBlank()) {
            throw new IllegalArgumentException("clientId and clientSecretEncrypted are required to enable a provider");
        }
        this.clientId = clientId;
        this.clientSecretEncrypted = clientSecretEncrypted;
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }
}
