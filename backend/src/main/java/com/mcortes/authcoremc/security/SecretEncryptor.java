package com.mcortes.authcoremc.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Application-level, reversible encryption (AES-256-GCM) for secrets that
 * must be readable in clear text again later — unlike a user password
 * (which only ever needs comparing, so it's hashed with Argon2id and never
 * decrypted), this is for the TOTP secret (this ticket) and the social
 * login {@code client_secret} per tenant (ticket 006). See
 * docs/ARQUITECTURA.md decision 6 for why this is a deliberate exception to
 * "standard" PII encryption.
 *
 * <p>⚠️ {@code app.secret-encryption-key} ships with a dev-only default so
 * local development and tests work out of the box. Any real deployment
 * MUST override it with {@code APP_SECRET_ENCRYPTION_KEY} (a 32-byte key,
 * base64-encoded — generate one with {@code openssl rand -base64 32}). The
 * shipped default is public (it's in this git history), so anything
 * encrypted with it offers no real protection — see docs/README.md.
 */
@Component
public class SecretEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryptor(
            @Value("${app.secret-encryption-key:RrHtxQQxrBRFOMu/D1TuAqDeq/eANE++OIlU9tkFhbY=}") String base64Key) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(combined, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }
}
