package com.mcortes.authcoremc.web;

import java.time.Instant;
import java.util.UUID;

/** Ticket 062 -- una fila de "Sesiones activas". `browser`/`os` se derivan del `userAgent` crudo en el momento de leer, ver {@link com.mcortes.authcoremc.security.UserAgentParser}. */
public record SessionSummary(UUID id, String browser, String os, Instant createdAt, Instant lastUsedAt, boolean current) {}
