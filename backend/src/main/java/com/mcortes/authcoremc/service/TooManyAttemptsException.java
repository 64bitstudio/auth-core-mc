package com.mcortes.authcoremc.service;

/** Thrown when {@link com.mcortes.authcoremc.security.LoginRateLimiter} has blocked further attempts. */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(String message) {
        super(message);
    }
}
