package com.mcortes.authcoremc.geoip;

/**
 * Ticket 070 -- ciudad/país resueltos de una IP, para "Sesiones activas"
 * (Mi Perfil de galgoth-studio, alineación con su mockup original). Ambos
 * campos pueden faltar de forma independiente (una IP puede resolver a un
 * país sin ciudad reconocida en GeoLite2) -- el caller decide cómo
 * mostrarlo (ver {@code SessionSummary}).
 */
public record GeoLocation(String city, String country) {}
