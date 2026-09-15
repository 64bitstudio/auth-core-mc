package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.LinkIntentSession;
import com.mcortes.authcoremc.oauth2.SocialRegistrationId;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.ExternalIdentityLinkService;
import com.mcortes.authcoremc.service.UnsupportedProviderException;
import com.mcortes.authcoremc.service.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 063 ("Vincular cuenta social nueva desde el perfil", Mi Perfil de
 * galgoth-studio, HU-7/HU-8) — mismo mecanismo de auth que
 * {@link AccountProfileController}. {@code client_id} se toma del claim
 * {@code aud} del propio JWT (el mismo valor que el header {@code
 * X-Client-Id} de todo el resto del proyecto) — no hace falta que el
 * caller lo repita, ya viaja firmado en el token.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountLinkProviderController {

    private final ClientContextResolver clientContextResolver;
    private final UserRepository userRepository;
    private final ExternalIdentityLinkService externalIdentityLinkService;

    public AccountLinkProviderController(
            ClientContextResolver clientContextResolver,
            UserRepository userRepository,
            ExternalIdentityLinkService externalIdentityLinkService) {
        this.clientContextResolver = clientContextResolver;
        this.userRepository = userRepository;
        this.externalIdentityLinkService = externalIdentityLinkService;
    }

    @GetMapping("/connected-providers")
    public List<ConnectedProviderSummary> connectedProviders(@AuthenticationPrincipal Jwt jwt) {
        User user = userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow(UserNotFoundException::new);
        return externalIdentityLinkService.listConnected(user);
    }

    /**
     * Guarda la intención de vínculo en la sesión HTTP (ver
     * {@link LinkIntentSession}) y devuelve la URL a la que el frontend debe
     * navegar el navegador COMPLETO (no una llamada XHR) — el mismo
     * mecanismo `/oauth2/authorization/{registrationId}` que ya usa el
     * login social.
     */
    @PostMapping("/link-provider/{provider}")
    public LinkProviderResponse linkProvider(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String provider, HttpServletRequest request) {
        IdentityProviderType providerType = parseSupportedProvider(provider);

        String clientId = jwt.getAudience().isEmpty() ? null : jwt.getAudience().get(0);
        IdentityClient client = clientContextResolver.resolveClient(clientId);

        LinkIntentSession.store(request, UUID.fromString(jwt.getSubject()));

        String registrationId = SocialRegistrationId.of(client.getId(), providerType).toString();
        return new LinkProviderResponse("/oauth2/authorization/" + registrationId);
    }

    private static IdentityProviderType parseSupportedProvider(String raw) {
        IdentityProviderType provider;
        try {
            provider = IdentityProviderType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw new UnsupportedProviderException("Unknown provider: " + raw);
        }
        if (!ExternalIdentityLinkService.isSupported(provider)) {
            throw new UnsupportedProviderException("Linking is not available yet for provider: " + provider);
        }
        return provider;
    }
}
