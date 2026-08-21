package com.mcortes.authcoremc.security;

import com.mcortes.authcoremc.domain.Tenant;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption for secrets belonging to external clients (ticket
 * 017 — client_secret of a tenant's own OAuth provider credentials, see
 * {@code TenantIdentityProviderService}). Each tenant gets its own AES-256
 * data-key, generated here and wrapped by {@link VaultTransitEncryptor};
 * Vault itself never sees the actual secret, only ever wraps/unwraps the
 * small data-key. If a single tenant's wrapped data-key or DB row leaks,
 * the blast radius is that one tenant — the property the previous
 * single-static-key design ({@link SecretEncryptor}, still used as-is for
 * TOTP secrets, out of scope here) didn't have.
 *
 * <p>Deliberately its own small AES/GCM implementation rather than
 * generalizing {@link SecretEncryptor} to accept a key per call — that
 * class's single fixed key is a deliberate, narrower design for the
 * platform's own secrets (TOTP); parameterizing it risks weakening that
 * guarantee for a use case it was never meant to serve.
 */
@Component
public class TenantSecretEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int DATA_KEY_BITS = 256;

    private final VaultTransitEncryptor vaultTransitEncryptor;
    private final SecureRandom random = new SecureRandom();

    public TenantSecretEncryptor(VaultTransitEncryptor vaultTransitEncryptor) {
        this.vaultTransitEncryptor = vaultTransitEncryptor;
    }

    /** Generates a fresh AES-256 data-key for a tenant and wraps it via Vault — the result is what gets stored on {@code Tenant.wrappedDataKey}. */
    public String newWrappedDataKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(DATA_KEY_BITS, random);
            SecretKey dataKey = keyGenerator.generateKey();
            return vaultTransitEncryptor.wrap(dataKey.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate a tenant data-key", e);
        }
    }

    /** Generates and sets this tenant's wrapped data-key if it doesn't have one yet — the caller is responsible for persisting the tenant afterward. Idempotent: does nothing if already set. */
    public String ensureWrappedDataKey(Tenant tenant) {
        if (tenant.getWrappedDataKey() == null) {
            tenant.setWrappedDataKey(newWrappedDataKey());
        }
        return tenant.getWrappedDataKey();
    }

    public String encrypt(String wrappedDataKey, String plaintext) {
        SecretKeySpec dataKey = unwrapDataKey(wrappedDataKey);
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt tenant secret", e);
        }
    }

    public String decrypt(String wrappedDataKey, String encoded) {
        SecretKeySpec dataKey = unwrapDataKey(wrappedDataKey);
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(combined, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt tenant secret", e);
        }
    }

    private SecretKeySpec unwrapDataKey(String wrappedDataKey) {
        byte[] rawKey = vaultTransitEncryptor.unwrap(wrappedDataKey);
        return new SecretKeySpec(rawKey, "AES");
    }
}
