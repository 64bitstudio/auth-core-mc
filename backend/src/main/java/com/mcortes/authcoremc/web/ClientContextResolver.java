package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import org.springframework.stereotype.Component;

/**
 * Resolves which tenant an incoming request belongs to, from the
 * {@code X-Client-Id} header identifying a registered
 * {@link com.mcortes.authcoremc.domain.IdentityClient}. This is the
 * decision flagged as open in docs/API.md when ticket 001 was written;
 * ticket 002 settles it. Ticket 007's OAuth2 endpoints will use the
 * standard {@code client_id} request parameter instead — this header is
 * specifically for the plain REST endpoints (register/login/etc).
 */
@Component
public class ClientContextResolver {

    private final IdentityClientRepository identityClientRepository;

    public ClientContextResolver(IdentityClientRepository identityClientRepository) {
        this.identityClientRepository = identityClientRepository;
    }

    public Tenant resolveTenant(String clientId) {
        return resolveClient(clientId).getTenant();
    }

    /** Like {@link #resolveTenant}, but for callers (ticket 007) that also need the client's firstParty flag. */
    public IdentityClient resolveClient(String clientId) {
        return identityClientRepository.findByClientId(clientId).orElseThrow(() -> new UnknownClientException(clientId));
    }
}
