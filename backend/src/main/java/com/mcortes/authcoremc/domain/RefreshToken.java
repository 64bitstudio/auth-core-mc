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

    /** Ticket 070 -- revierte la decisión explícita del ticket 062 de no geolocalizar. Nullable por el mismo motivo que {@code userAgent}: algunos callers/tests históricos no lo tienen a mano. Nunca se resuelve a ciudad aquí -- ver {@code GeoIpService}, resuelto en caliente al leer. */
    @Column(name = "client_ip")
    private String clientIp;

    protected RefreshToken() {
        // JPA
    }

    /** Constructor histórico (ticket 001) -- sigue sin exigir `userAgent`/`clientIp` para no romper los callers/tests que ya existían antes de los tickets 062/070. */
    public RefreshToken(User user, IdentityClient client, String tokenHash, Instant expiresAt) {
        this(user, client, tokenHash, expiresAt, null, null);
    }

    /** Constructor del ticket 062 -- sigue sin exigir `clientIp` para no romper los callers/tests que ya existían antes del ticket 070. */
    public RefreshToken(User user, IdentityClient client, String tokenHash, Instant expiresAt, String userAgent) {
        this(user, client, tokenHash, expiresAt, userAgent, null);
    }

    public RefreshToken(
            User user, IdentityClient client, String tokenHash, Instant expiresAt, String userAgent, String clientIp) {
        this.user = user;
        this.client = client;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.userAgent = userAgent;
        this.clientIp = clientIp;
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

    public String getClientIp() {
        return clientIp;
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
