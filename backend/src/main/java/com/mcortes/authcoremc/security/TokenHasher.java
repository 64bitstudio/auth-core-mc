package com.mcortes.authcoremc.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes high-entropy opaque tokens (refresh tokens) before storing them —
 * deliberately SHA-256, not Argon2id: Argon2id is intentionally slow to
 * resist brute-forcing a low-entropy secret (a human password); a 256-bit
 * random refresh token is already unguessable, so a slow hash would only
 * add latency with no security benefit. Argon2id stays reserved for actual
 * passwords (docs/ARQUITECTURA.md decision 5).
 */
public final class TokenHasher {

    private TokenHasher() {}

    public static String sha256(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
