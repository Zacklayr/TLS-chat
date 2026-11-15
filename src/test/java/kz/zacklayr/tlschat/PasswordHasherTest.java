package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void verifiesOnlyThePasswordUsedForHashing() {
        PasswordHasher.PasswordHash hash = PasswordHasher.hash("correct-password".toCharArray());

        assertTrue(PasswordHasher.verify("correct-password".toCharArray(), hash.salt(), hash.hash()));
        assertFalse(PasswordHasher.verify("wrong-password".toCharArray(), hash.salt(), hash.hash()));
    }
}
