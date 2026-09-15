package com.mcortes.authcoremc.service;

/**
 * Ticket 069 -- verificación en vivo de galgoth-studio#079 (menú "···" de
 * "Cuentas conectadas" en el mockup) expuso que desvincular no tenía
 * ninguna protección: una cuenta social-only (sin contraseña) con un solo
 * proveedor vinculado podía desvincularlo y quedarse sin ninguna forma de
 * volver a entrar. Se lanza cuando el usuario NO tiene contraseña
 * (`passwordHash == null`) Y este es el ÚLTIMO proveedor que le queda —
 * el caller debe establecer una contraseña primero, o dejar al menos un
 * proveedor vinculado.
 */
public class CannotUnlinkLastLoginMethodException extends RuntimeException {

    public CannotUnlinkLastLoginMethodException() {
        super("Cannot unlink your only way to sign in -- set a password first");
    }
}
