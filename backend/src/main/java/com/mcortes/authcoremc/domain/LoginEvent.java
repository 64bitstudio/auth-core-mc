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
 * One login attempt (ticket 015) — success or failure, which provider, how
 * long it took. Feeds the admin panel's usage metrics (ticket 016). No
 * particioning (volumen bajo/moderado esperado, decisión tomada en la fase
 * de definición — ver docs/definiciones/panel-administracion-clientes.md).
 *
 * <p>{@code user} is nullable — a failed login with an unknown
 * identifier/wrong password never resolves to a real {@link User}, and
 * that's still a real event worth counting (see {@code
 * AuthenticationService#authenticate}, which throws before a user is ever
 * found in that case).
 */
@Entity
@Table(name = "login_event")
public class LoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(optional = true)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoginOutcome outcome;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected LoginEvent() {
        // JPA
    }

    public LoginEvent(Tenant tenant, User user, String provider, LoginOutcome outcome, int latencyMs) {
        this.tenant = tenant;
        this.user = user;
        this.provider = provider;
        this.outcome = outcome;
        this.latencyMs = latencyMs;
        this.occurredAt = Instant.now();
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

    public String getProvider() {
        return provider;
    }

    public LoginOutcome getOutcome() {
        return outcome;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
