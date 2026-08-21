package com.mcortes.authcoremc.web;

import java.time.Instant;
import java.util.List;

/**
 * Ticket 018: a snapshot for an incident responder — deliberately narrow
 * (counts + the most recent login activity), not a general-purpose data
 * export. No PII (no emails/names) — just enough to see whether the
 * system is up and roughly what's happening.
 */
public record BreakGlassDiagnosticsResponse(
        boolean databaseHealthy,
        long tenantCount,
        long activeTenantCount,
        long userCount,
        List<RecentLoginEvent> recentLoginEvents) {

    public record RecentLoginEvent(String tenantName, String provider, String outcome, Instant occurredAt) {}
}
