package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/** Ticket 064 -- {@code confirmIdentifier} es el propio email o teléfono del usuario, reenviado tal cual: exige un paso deliberado (no solo un clic) antes de una acción irreversible. */
public record DeleteAccountRequest(@NotBlank String confirmIdentifier) {}
