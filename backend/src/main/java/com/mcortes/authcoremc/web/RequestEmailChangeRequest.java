package com.mcortes.authcoremc.web;

/**
 * Hallazgo real de seguridad (2026-09-15): antes traía también {@code
 * userId}, confiado tal cual del body sin ninguna autenticación real —
 * cualquiera que conociera/adivinara el UUID de otra cuenta podía pedir
 * que su correo de cambio de dirección llegara a un correo del atacante
 * (el dueño real de la cuenta nunca recibía ningún aviso). El usuario
 * ahora sale exclusivamente del JWT verificado (ver {@link
 * EmailChangeController}), nunca del body.
 */
public record RequestEmailChangeRequest(String newEmail) {}
