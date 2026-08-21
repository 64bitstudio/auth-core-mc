package com.mcortes.authcoremc.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238, on top of HOTP/RFC 4226) — compatible with Google
 * Authenticator/Authy: 6-digit codes, 30-second steps, HMAC-SHA1. No
 * external library: this is a small, well-specified algorithm and pulling
 * in a dependency for it wasn't worth it.
 */
public final class Totp {

    private static final int CODE_DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    /** The code valid right now for {@code base32Secret} — what an authenticator app would be showing. */
    public static String currentCode(String base32Secret) {
        return codeAt(base32Secret, Instant.now().getEpochSecond() / STEP_SECONDS);
    }

    /** A fresh random secret, base32-encoded (what you'd show as a QR/manual-entry code). */
    public static String generateSecret() {
        byte[] bytes = new byte[20]; // 160 bits, standard for HmacSHA1-based TOTP
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Verifies {@code code} against the current 30s window and one step of
     * clock drift on either side — and, separately, the caller (TotpService)
     * is responsible for the Redis-backed check that a matched window can't
     * be replayed a second time within its validity, which is not something
     * this stateless function can enforce on its own.
     */
    public static boolean verify(String base32Secret, String code) {
        long currentWindow = Instant.now().getEpochSecond() / STEP_SECONDS;
        for (long window = currentWindow - 1; window <= currentWindow + 1; window++) {
            if (codeAt(base32Secret, window).equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** Which window (if any, among current ±1) a code matched — used for replay detection. */
    public static long matchedWindow(String base32Secret, String code) {
        long currentWindow = Instant.now().getEpochSecond() / STEP_SECONDS;
        for (long window = currentWindow - 1; window <= currentWindow + 1; window++) {
            if (codeAt(base32Secret, window).equals(code)) {
                return window;
            }
        }
        return -1;
    }

    private static String codeAt(String base32Secret, long counter) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int code = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", code);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute TOTP code", e);
        }
    }

    private static String base32Encode(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return result.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().toUpperCase().replace("=", "");
        int outputLength = clean.length() * 5 / 8;
        byte[] result = new byte[outputLength];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return result;
    }
}
