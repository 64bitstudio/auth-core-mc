package com.mcortes.authcoremc.web;

import java.time.Instant;
import java.util.Map;

/**
 * Ticket 016: usage metrics for a tenant over a date range, aggregated from
 * {@code login_event} (ticket 015). All-zero fields for a range with no
 * activity are a valid, normal response (200) — the UI treats that as an
 * empty state, not an error (see {@code admin-metrics.html}).
 */
public record TenantMetricsResponse(
        Instant from,
        Instant to,
        long totalLogins,
        long successCount,
        long failureCount,
        double errorRate,
        Map<String, Long> byProvider,
        long activeUsers,
        long registeredUsers,
        double avgLatencyMs) {}
