package com.mcortes.authcoremc.service;

/**
 * Ticket 064 -- llamar a galgoth-studio para purgar los proyectos del
 * usuario (síncrono, antes de tocar cualquier dato en esta base) falló:
 * la integración no está configurada, o la llamada HTTP en sí falló
 * (red, timeout, respuesta no-2xx). La cuenta NO se desactiva en
 * ninguno de los dos casos -- decisión explícita de Marco, ver
 * {@code AccountDeletionService}: nunca dejar una cuenta "eliminada" con
 * proyectos públicos todavía visibles en Explorar.
 */
public class AccountDeletionFailedException extends RuntimeException {

    public AccountDeletionFailedException(String message) {
        super(message);
    }

    public AccountDeletionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
