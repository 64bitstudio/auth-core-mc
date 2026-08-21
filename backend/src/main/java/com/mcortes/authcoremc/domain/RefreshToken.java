package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token issued to a user for a given client. Only the hash is
 * persisted — the raw token is never stored (docs/BASE_DE_DATOS.md).
 * {@code revoked} is the durable record; instant revocation for an
 * already-issued access token is handled via Redis (ticket 007), not here.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private IdentityClient client;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(User user, IdentityClient client, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.client = client;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public IdentityClient getClient() {
        return client;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void revoke() {
        this.revoked = true;
    }
}
