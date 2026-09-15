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

    /** Ticket 062 -- "Sesiones activas" (Mi Perfil, galgoth-studio). {@code userAgent} nullable (capturado solo cuando el caller lo tiene a mano). */
    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected RefreshToken() {
        // JPA
    }

    /** Constructor histórico (ticket 001) -- sigue sin exigir `userAgent` para no romper los callers/tests que ya existían antes del ticket 062. */
    public RefreshToken(User user, IdentityClient client, String tokenHash, Instant expiresAt) {
        this(user, client, tokenHash, expiresAt, null);
    }

    public RefreshToken(User user, IdentityClient client, String tokenHash, Instant expiresAt, String userAgent) {
        this.user = user;
        this.client = client;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.userAgent = userAgent;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastUsedAt = now;
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

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void revoke() {
        this.revoked = true;
    }

    /** Ticket 062 -- se llama en cada `POST /api/v1/token/refresh` exitoso sobre este token. */
    public void touch() {
        this.lastUsedAt = Instant.now();
    }
}
