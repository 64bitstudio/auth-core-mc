package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.service.TokenPair;

public record LoginResponse(UserResponse user, TokenPair tokens) {}
