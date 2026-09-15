package com.mcortes.authcoremc.web;

/** Ticket 063 -- el frontend navega el navegador entero a {@code redirectUrl} (no es una llamada XHR, es la misma navegación de página completa que ya usa el login social). */
public record LinkProviderResponse(String redirectUrl) {}
