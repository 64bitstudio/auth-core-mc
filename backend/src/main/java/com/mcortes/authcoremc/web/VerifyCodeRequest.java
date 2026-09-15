package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotBlank;

/** Ver docstring de {@link RequestEmailChangeRequest} -- mismo hallazgo, mismo criterio: el usuario sale del JWT, nunca del body. */
public record VerifyCodeRequest(@NotBlank String code) {}
