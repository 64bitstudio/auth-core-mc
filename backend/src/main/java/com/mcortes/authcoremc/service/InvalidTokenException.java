package com.mcortes.authcoremc.service;

/** Thrown when a one-time token (verification, change-email, password reset) is missing/expired/already used. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
