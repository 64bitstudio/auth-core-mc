package com.mcortes.authcoremc.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * Ticket 063 ("Vincular cuenta social nueva desde el perfil", Mi Perfil de
 * galgoth-studio, Diseño técnico §4 del documento de definición). El
 * callback de OAuth2 (Google/Facebook devolviendo el control) es una
 * navegación de navegador normal (GET), sin header {@code Authorization} —
 * no hay forma de saber "quién iba a vincular esto" salvo llevarlo en el
 * propio viaje de ida y vuelta. Se reutiliza la MISMA sesión HTTP que
 * Spring ya crea para correlacionar el redirect de {@code oauth2Login} con
 * su callback (ver el Javadoc de {@code SecurityConfig} sobre esa sesión:
 * "no se usa como auth continua, solo como correlación") — nunca el
 * {@code state} de OAuth2 (eso es responsabilidad interna de Spring
 * Security, un CSRF token, no un lugar para meter datos de negocio).
 *
 * <p>Un solo uso: {@link #consume} borra el atributo al leerlo, para que un
 * login normal posterior en la misma sesión de navegador nunca "herede" una
 * intención de vínculo vieja.
 *
 * <p>Pública (no package-private): quien escribe el atributo
 * ({@code AccountLinkProviderController}) vive en el paquete {@code web},
 * no en este — mismo cruce de paquetes que ya existe entre
 * {@code SocialLoginSuccessHandler} (acá) y {@code SocialExchangeController}
 * (en {@code web}) para {@code EXCHANGE_PURPOSE}.
 */
public final class LinkIntentSession {

    private static final String ATTRIBUTE = "link-intent-user-id";

    private LinkIntentSession() {}

    public static void store(HttpServletRequest request, UUID userId) {
        request.getSession(true).setAttribute(ATTRIBUTE, userId.toString());
    }

    public static Optional<UUID> consume(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(ATTRIBUTE);
        session.removeAttribute(ATTRIBUTE);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.toString()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
