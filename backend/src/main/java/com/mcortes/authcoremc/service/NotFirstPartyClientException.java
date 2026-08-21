package com.mcortes.authcoremc.service;

/** Thrown when a non-first-party client attempts the direct (non-redirect) login grant — see ticket 007. */
public class NotFirstPartyClientException extends RuntimeException {

    public NotFirstPartyClientException() {
        super("This client is not authorized to use the direct login grant — only first-party clients can. "
                + "Third-party clients must use Authorization Code + PKCE via /oauth2/authorize.");
    }
}
