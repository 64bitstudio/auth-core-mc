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
import java.time.Instant;
import java.util.UUID;

/**
 * Links an {@link User} to their identity at an external provider (Google/Facebook) —
 * ticket 035, first ticket of the real social login epic
 * (docs/definiciones/login-social-real.md, Diseño técnico, decisión 6).
 *
 * <p>{@code providerUserId} is the provider's stable subject identifier
 * (Google's {@code sub}, Facebook's {@code id}) — never the email, which can
 * change on the provider's side.
 *
 * <p>A dedicated table (not columns on {@code app_user}) so a single user can
 * link more than one provider at a time: one row per (user, provider), and
 * the same social account can never be linked twice within the same tenant —
 * both enforced at the database level (see V8__external_identity.sql).
 */
@Entity
@Table(name = "external_identity")
public class ExternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdentityProviderType provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected ExternalIdentity() {
        // JPA
    }

    public ExternalIdentity(Tenant tenant, User user, IdentityProviderType provider, String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId must not be blank");
        }
        this.tenant = tenant;
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.linkedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public User getUser() {
        return user;
    }

    public IdentityProviderType getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
