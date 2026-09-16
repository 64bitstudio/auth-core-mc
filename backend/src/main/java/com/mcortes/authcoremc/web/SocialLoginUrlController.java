package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.oauth2.SocialRegistrationId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 072 -- el último tramo faltante de {@code
 * docs/definiciones/login-social-real.md}: un visitante SIN sesión (por
 * definición, en {@code /ui/login}/{@code /ui/register} o su equivalente
 * en un cliente con UI propia) no tiene forma de construir
 * {@code /oauth2/authorization/{registrationId}} por su cuenta -- ese
 * {@code registrationId} empaqueta el UUID interno del {@link
 * IdentityClient} ({@link SocialRegistrationId}), un detalle de
 * implementación que ningún frontend debe conocer ni hardcodear (a
 * diferencia de su propio {@code client_id} legible, que sí es público
 * por diseño). {@link AccountLinkProviderController#linkProvider}
 * resuelve exactamente esta misma URL para un usuario YA autenticado
 * (vincular una cuenta); este endpoint es su equivalente público, sin
 * JWT, para el caso "iniciar sesión por primera vez" -- por eso no
 * escribe ningún {@code LinkIntentSession} (nada que correlacionar
 * todavía, el usuario ni siquiera existe o no está identificado hasta
 * que vuelve del proveedor).
 *
 * <p>Público a propósito, mismo criterio que {@code
 * /oauth2/authorization/**} (que este endpoint solo resuelve, nunca
 * reemplaza) y {@code /api/v1/oauth2/social-exchange}: es un paso previo
 * a la sesión, no uno que la requiera. No revela nada sensible -- el
 * cliente y el proveedor ya son públicos por otras vías (el propio
 * {@code X-Client-Id} que cualquier caller ya manda, y la lista de
 * proveedores soportados es fija).
 */
@RestController
@RequestMapping("/api/v1/oauth2")
public class SocialLoginUrlController {

    private final ClientContextResolver clientContextResolver;
    private final String baseUrl;

    public SocialLoginUrlController(
            ClientContextResolver clientContextResolver, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.clientContextResolver = clientContextResolver;
        this.baseUrl = baseUrl;
    }

    @GetMapping("/login-url/{provider}")
    public SocialLoginUrlResponse loginUrl(@RequestHeader("X-Client-Id") String clientId, @PathVariable String provider) {
        IdentityProviderType providerType = SupportedProvider.parse(provider);
        IdentityClient client = clientContextResolver.resolveClient(clientId);
        String registrationId = SocialRegistrationId.of(client.getId(), providerType).toString();
        return new SocialLoginUrlResponse(baseUrl + "/oauth2/authorization/" + registrationId);
    }
}
