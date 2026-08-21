package com.mcortes.authcoremc.web;

/** Uniform error shape for every endpoint — see docs/API.md "Convenciones". */
public record ErrorResponse(String error, String message) {}
