package com.mcortes.authcoremc.service;

/** Thrown when a raw password does not satisfy {@link com.mcortes.authcoremc.security.PasswordPolicy}. */
public class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String message) {
        super(message);
    }
}
