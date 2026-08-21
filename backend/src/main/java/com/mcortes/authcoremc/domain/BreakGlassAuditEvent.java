package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Ticket 018: one break-glass call — success OR failure, always recorded.
 * A FAILURE row (wrong secret, wrong TOTP, disallowed IP, not configured)
 * is just as important as a SUCCESS one for this door: it's the first
 * thing an incident retrospective should be able to answer — who tried to
 * use break-glass, from where, and whether it worked.
 *
 * <p>No FK to {@code tenant} on purpose — see the migration's comment.
 */
@Entity
@Table(name = "break_glass_audit_event")
public class BreakGlassAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String operator;

    @Column(name = "remote_ip", nullable = false)
    private String remoteIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakGlassAction action;

    @Column(name = "target_tenant_id")
    private UUID targetTenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakGlassOutcome outcome;

    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected BreakGlassAuditEvent() {
        // JPA
    }

    public BreakGlassAuditEvent(
            String operator,
            String remoteIp,
            BreakGlassAction action,
            UUID targetTenantId,
            BreakGlassOutcome outcome,
            String detail) {
        this.operator = operator;
        this.remoteIp = remoteIp;
        this.action = action;
        this.targetTenantId = targetTenantId;
        this.outcome = outcome;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOperator() {
        return operator;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public BreakGlassAction getAction() {
        return action;
    }

    public UUID getTargetTenantId() {
        return targetTenantId;
    }

    public BreakGlassOutcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
