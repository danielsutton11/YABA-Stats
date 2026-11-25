package com.dsuttz.yaba;

import com.dsuttz.yaba.config.KeyCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class KeyCryptoTest {

    private final KeyCrypto crypto = new KeyCrypto(new MockEnvironment().withProperty("YABA_KEY_SECRET", "a-long-test-secret-value"));

    @Test
    void roundTripsAndUsesFreshIv() {
        String a = crypto.encrypt("AAAAAAAAAAAAAAAA"), b = crypto.encrypt("AAAAAAAAAAAAAAAA");
        assertNotEquals(a, b);
        assertFalse(a.contains("AAAAAAAAAAAAAAAA"));
        assertEquals("AAAAAAAAAAAAAAAA", crypto.decrypt(a));
        assertEquals("AAAAAAAAAAAAAAAA", crypto.decrypt(b));
    }

    @Test
    void fingerprintIsStableAndKeyed() {
        KeyCrypto other = new KeyCrypto(new MockEnvironment().withProperty("YABA_KEY_SECRET", "a-different-secret-value"));
        assertEquals(crypto.fingerprint("AAAAAAAAAAAAAAAA"), crypto.fingerprint("AAAAAAAAAAAAAAAA"));
        assertNotEquals(crypto.fingerprint("AAAAAAAAAAAAAAAA"), other.fingerprint("AAAAAAAAAAAAAAAA"));
    }

    @Test
    void wrongSecretCannotDecrypt() {
        KeyCrypto other = new KeyCrypto(new MockEnvironment().withProperty("YABA_KEY_SECRET", "a-different-secret-value"));
        assertThrows(IllegalStateException.class, () -> other.decrypt(crypto.encrypt("AAAAAAAAAAAAAAAA")));
    }

    @Test
    void refusesMissingSecret() {
        assertThrows(IllegalStateException.class, () -> new KeyCrypto(new MockEnvironment()));
    }
}
