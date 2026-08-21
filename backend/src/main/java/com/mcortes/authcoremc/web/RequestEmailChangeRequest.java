package com.mcortes.authcoremc.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RequestEmailChangeRequest(@NotNull UUID userId, String newEmail) {}
