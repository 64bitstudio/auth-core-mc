package com.mcortes.authcoremc.service;

/**
 * Ticket 063 -- el perfil social que se intentó vincular ya pertenece a
 * OTRO usuario del mismo tenant. Nunca se desvincula del original ni se
 * reasigna en silencio; el caller debe intentar con una cuenta social
 * distinta.
 */
public class ProviderAlreadyLinkedException extends RuntimeException {

    public ProviderAlreadyLinkedException() {
        super("This provider account is already linked to another user");
    }
}
