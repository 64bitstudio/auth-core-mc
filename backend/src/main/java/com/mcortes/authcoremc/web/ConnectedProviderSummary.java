package com.mcortes.authcoremc.web;

/** Ticket 063 -- una fila de "Cuentas conectadas". {@code provider} es el nombre del enum (`"GOOGLE"`/`"FACEBOOK"`). */
public record ConnectedProviderSummary(String provider, boolean linked) {}
