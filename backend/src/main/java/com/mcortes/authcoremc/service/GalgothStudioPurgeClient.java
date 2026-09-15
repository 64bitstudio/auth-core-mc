package com.mcortes.authcoremc.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ticket 064 ("eliminar cuenta"): llama al endpoint interno de
 * galgoth-studio (ticket 091 de ese repo,
 * {@code POST /api/internal/users/{userId}/purge-projects}) que
 * soft-deletea TODOS los proyectos del usuario (públicos y privados)
 * ANTES de desactivar la cuenta acá -- mismo orden que
 * {@code AccountDeletionService} exige.
 *
 * <p>Mismo patrón que {@code ResendEmailSender} (notification package):
 * {@code RestClient} inyectado, config vía {@code @Value}, falla ruidoso
 * (nunca silencioso) si {@code GALGOTH_STUDIO_INTERNAL_URL}/
 * {@code GALGOTH_INTERNAL_SECRET} no están configurados. El secreto
 * compartido (header {@code X-Internal-Secret}) es el mismo valor por
 * ambiente que ya generó y desplegó galgoth-studio en su propio ticket
 * 091 -- coordinado a mano entre ambos repos, no generado acá.
 */
@Component
public class GalgothStudioPurgeClient {

    private final RestClient restClient;
    private final boolean configured;

    public GalgothStudioPurgeClient(
            RestClient.Builder restClientBuilder,
            @Value("${galgoth-studio.internal-url:}") String internalUrl,
            @Value("${galgoth-studio.internal-secret:}") String internalSecret) {
        this.configured = internalUrl != null && !internalUrl.isBlank()
                && internalSecret != null && !internalSecret.isBlank();
        this.restClient = restClientBuilder
                .baseUrl(internalUrl == null ? "" : internalUrl)
                .defaultHeader("X-Internal-Secret", internalSecret == null ? "" : internalSecret)
                .build();
    }

    public void purgeProjects(UUID userId) {
        if (!configured) {
            throw new AccountDeletionFailedException(
                    "GALGOTH_STUDIO_INTERNAL_URL/GALGOTH_INTERNAL_SECRET no están configurados -- no se puede "
                            + "purgar los proyectos de galgoth-studio antes de eliminar la cuenta.");
        }
        try {
            restClient
                    .post()
                    .uri("/api/internal/users/{userId}/purge-projects", userId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new AccountDeletionFailedException(
                    "No se pudo purgar los proyectos de galgoth-studio para esta cuenta -- eliminación abortada.", e);
        }
    }
}
