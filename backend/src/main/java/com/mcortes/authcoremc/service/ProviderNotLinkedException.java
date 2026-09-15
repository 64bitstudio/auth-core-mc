package com.mcortes.authcoremc.service;

/** Ticket 069 -- se intentó desvincular un proveedor que este usuario nunca vinculó (o ya desvinculó antes). */
public class ProviderNotLinkedException extends RuntimeException {

    public ProviderNotLinkedException() {
        super("This provider is not linked to your account");
    }
}
