package com.mcortes.authcoremc.service;

/** An issued access+refresh token pair — see {@link DirectTokenService}. */
public record TokenPair(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}
