package com.mcortes.authcoremc.web;

import java.time.Instant;
import java.util.UUID;

/**
 * Ticket 062 -- una fila de "Sesiones activas". `browser`/`os` se derivan
 * del `userAgent` crudo en el momento de leer, ver
 * {@link com.mcortes.authcoremc.security.UserAgentParser}.
 *
 * <p>Ticket 070 -- `city`/`country` (ambos nullable de forma
 * independiente, ver {@code GeoLocation}) se resuelven en caliente desde
 * `clientIp` (ver {@code GeoIpService}) -- nunca guardados, así que una
 * actualización de la base de datos de MaxMind aplica retroactivamente a
 * sesiones ya existentes, mismo criterio que `browser`/`os`.
 */
public record SessionSummary(
        UUID id, String browser, String os, String city, String country, Instant createdAt, Instant lastUsedAt, boolean current) {}
