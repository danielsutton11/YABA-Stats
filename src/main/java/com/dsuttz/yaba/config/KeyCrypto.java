package com.dsuttz.yaba.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts Torn API keys before they go into Postgres (AES-256-GCM, random 12-byte IV per value)
 * and makes an HMAC-SHA256 fingerprint so a key can be looked up without storing it in plain text.
 * Both keys are derived from YABA_KEY_SECRET, which lives only in Railway's variables.
 * Changing YABA_KEY_SECRET makes the stored keys unreadable — re-add them from the sheet if you do.
 */
@Component
public class KeyCrypto {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12, TAG_BITS = 128;

    private final SecretKeySpec aes;
    private final SecretKeySpec hmac;
    private final SecureRandom random = new SecureRandom();

    public KeyCrypto(Environment env) {
        String secret = env.getProperty("YABA_KEY_SECRET", env.getProperty("yaba.key-secret", ""));
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("Set YABA_KEY_SECRET (at least 16 characters) — it encrypts the stored Torn API keys");
        }
        this.aes = new SecretKeySpec(derive(secret, "yaba-aes"), "AES");
        this.hmac = new SecretKeySpec(derive(secret, "yaba-hmac"), "HmacSHA256");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) throw new IllegalArgumentException("Not an encrypted key");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt a stored key — was YABA_KEY_SECRET changed?", e);
        }
    }

    public String fingerprint(String plain) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmac);
            return Base64.getEncoder().encodeToString(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Fingerprint failed", e);
        }
    }

    private static byte[] derive(String secret, String label) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(label.getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            return d.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
