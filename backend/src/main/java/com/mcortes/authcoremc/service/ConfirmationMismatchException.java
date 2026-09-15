package com.mcortes.authcoremc.service;

/** Ticket 064 -- el {@code confirmIdentifier} enviado no coincide con el email/teléfono real del usuario que pide eliminar su cuenta. */
public class ConfirmationMismatchException extends RuntimeException {

    public ConfirmationMismatchException() {
        super("confirmIdentifier does not match this account's email or phone");
    }
}
