package com.mcortes.authcoremc.service;

/** Thrown when configuring a provider this service doesn't actually support enabling yet (Apple — see ticket 006). */
public class UnsupportedProviderException extends RuntimeException {

    public UnsupportedProviderException(String message) {
        super(message);
    }
}
