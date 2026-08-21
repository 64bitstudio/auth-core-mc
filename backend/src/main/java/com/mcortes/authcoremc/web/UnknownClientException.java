package com.mcortes.authcoremc.web;

/**
 * Thrown when the {@code X-Client-Id} header on an incoming request doesn't
 * match any registered {@link com.mcortes.authcoremc.domain.IdentityClient}
 * — see docs/API.md and docs/ARQUITECTURA.md for why this header is how a
 * request identifies which tenant it belongs to.
 */
public class UnknownClientException extends RuntimeException {

    public UnknownClientException(String clientId) {
        super("Unknown client: " + clientId);
    }
}
