package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.TwoFactorMethod;
import jakarta.validation.constraints.NotNull;

/** Ver docstring de {@link RequestEmailChangeRequest} -- mismo hallazgo, mismo criterio: el usuario sale del JWT, nunca del body. */
public record ActivateTwoFactorRequest(@NotNull TwoFactorMethod method) {}
