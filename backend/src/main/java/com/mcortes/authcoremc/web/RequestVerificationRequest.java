package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RequestVerificationRequest(@NotNull UUID userId) {}
