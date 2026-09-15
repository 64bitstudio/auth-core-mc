package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.ExternalIdentityRepository;
import com.mcortes.authcoremc.web.ConnectedProviderSummary;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 063 ("Vincular cuenta social nueva desde el perfil", Mi Perfil de
 * galgoth-studio) — a diferencia de {@link SocialLoginUserResolver} (que
 * resuelve "¿a qué usuario pertenece este perfil?" durante un LOGIN), este
 * servicio vincula un proveedor a un usuario YA CONOCIDO (el que inició el
 * flujo desde su perfil, ya autenticado) — nunca inicia sesión como otro
 * usuario ni crea una cuenta nueva.
 */
@Service
public class ExternalIdentityLinkService {

    /** Apple queda fuera de alcance (documento de definición, "No incluye") -- solo se listan/vinculan Google y Facebook. */
    private static final Set<IdentityProviderType> SUPPORTED_PROVIDERS =
            EnumSet.of(IdentityProviderType.GOOGLE, IdentityProviderType.FACEBOOK);

    private final ExternalIdentityRepository externalIdentityRepository;

    public ExternalIdentityLinkService(ExternalIdentityRepository externalIdentityRepository) {
        this.externalIdentityRepository = externalIdentityRepository;
    }

    public static boolean isSupported(IdentityProviderType provider) {
        return SUPPORTED_PROVIDERS.contains(provider);
    }

    /**
     * @throws ProviderAlreadyLinkedException si {@code (tenant, provider, profile.providerUserId())}
     *     ya pertenece a OTRO usuario — nunca se reasigna ni se desvincula del original.
     */
    @Transactional
    public void link(Tenant tenant, User user, IdentityProviderType provider, SocialProfile profile) {
        externalIdentityRepository
                .findByTenantAndProviderAndProviderUserId(tenant, provider, profile.providerUserId())
                .ifPresentOrElse(
                        existing -> {
                            if (!existing.getUser().getId().equals(user.getId())) {
                                throw new ProviderAlreadyLinkedException();
                            }
                            // Ya vinculado a este mismo usuario -- idempotente, no-op.
                        },
                        () -> externalIdentityRepository.save(
                                new ExternalIdentity(tenant, user, provider, profile.providerUserId())));
    }

    /**
     * Ticket 069 -- hallazgo real de galgoth-studio#079: no existía ninguna
     * forma de desvincular, ni protección contra quedarse sin forma de
     * entrar. Ver {@link CannotUnlinkLastLoginMethodException}.
     *
     * @throws ProviderNotLinkedException si {@code provider} nunca estuvo vinculado a este usuario.
     * @throws CannotUnlinkLastLoginMethodException si el usuario no tiene contraseña y este es su único proveedor.
     */
    @Transactional
    public void unlink(User user, IdentityProviderType provider) {
        ExternalIdentity identity = externalIdentityRepository
                .findByUserAndProvider(user, provider)
                .orElseThrow(ProviderNotLinkedException::new);

        boolean hasPassword = user.getPasswordHash() != null;
        long linkedCount = externalIdentityRepository.countByUser(user);
        if (!hasPassword && linkedCount <= 1) {
            throw new CannotUnlinkLastLoginMethodException();
        }

        externalIdentityRepository.delete(identity);
    }

    @Transactional(readOnly = true)
    public List<ConnectedProviderSummary> listConnected(User user) {
        Set<IdentityProviderType> linked = externalIdentityRepository.findByUser(user).stream()
                .map(ExternalIdentity::getProvider)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(IdentityProviderType.class)));

        return SUPPORTED_PROVIDERS.stream()
                .sorted()
                .map(provider -> new ConnectedProviderSummary(provider.name(), linked.contains(provider)))
                .toList();
    }
}
