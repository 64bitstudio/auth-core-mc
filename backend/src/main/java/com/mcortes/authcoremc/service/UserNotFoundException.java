package com.mcortes.authcoremc.service;

/** Thrown when a given userId doesn't exist, or doesn't belong to the tenant resolved for the request. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("User not found");
    }
}
