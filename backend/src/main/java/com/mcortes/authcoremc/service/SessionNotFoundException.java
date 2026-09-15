package com.mcortes.authcoremc.service;

/** Ticket 062 -- la sesión pedida no existe o no pertenece al usuario autenticado; nunca se distingue cuál de las dos, mismo criterio que el resto del proyecto (no revelar existencia ajena). */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException() {
        super("Session not found");
    }
}
