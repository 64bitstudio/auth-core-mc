package com.mcortes.authcoremc.security;

/**
 * Ticket 062 ("Sesiones activas", Mi Perfil de galgoth-studio) — detección
 * ligera de navegador/sistema operativo a partir del header {@code
 * User-Agent} crudo. Deliberadamente simple (sin librería nueva): solo
 * cubre los casos que un usuario final realmente reconoce en una lista de
 * sesiones (Chrome/Firefox/Safari/Edge sobre Windows/macOS/Linux/Android/
 * iOS) — no es un detector exhaustivo de user-agents. Se parsea en el
 * momento de LEER (nunca se guarda ya parseado, ver
 * {@code AccountSessionsService}), así que una mejora futura del detector
 * aplica retroactivamente a sesiones ya existentes sin migrar nada.
 */
public final class UserAgentParser {

    private static final String UNKNOWN = "Desconocido";

    private UserAgentParser() {}

    public static String browser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        // Orden importa: Edge/Chrome incluyen "Safari" en su UA; OPR antes que Chrome.
        if (userAgent.contains("OPR/") || userAgent.contains("Opera")) {
            return "Opera";
        }
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/") && !userAgent.contains("Chrome/")) {
            return "Safari";
        }
        return UNKNOWN;
    }

    public static String os(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return UNKNOWN;
    }
}
