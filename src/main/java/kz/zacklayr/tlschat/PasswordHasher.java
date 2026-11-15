package kz.zacklayr.tlschat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    static PasswordHash hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        try {
            return new PasswordHash(
                    Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(derive(password, salt)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static boolean verify(char[] password, String salt, String expectedHash) {
        try {
            byte[] actual = derive(password, Base64.getDecoder().decode(salt));
            byte[] expected = Base64.getDecoder().decode(expectedHash);
            return java.security.MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt) {
        try {
            PBEKeySpec specification = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(specification).getEncoded();
            } finally {
                specification.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable", e);
        }
    }

    record PasswordHash(String salt, String hash) {
    }
}
