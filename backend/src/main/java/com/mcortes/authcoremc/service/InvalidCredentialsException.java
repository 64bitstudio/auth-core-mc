package com.mcortes.authcoremc.service;

/**
 * Thrown for any login failure — wrong identifier, wrong password, or
 * unverified/disabled account. Deliberately generic: never reveal which
 * part was wrong (that would let an attacker enumerate valid emails/phones).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
