package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedBackupAppTest {
    @Test
    void createsDecryptableBackupWithPersistentData(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("tls-chat.db"), "database");
        Files.createDirectories(temp.resolve("uploads"));
        Files.writeString(temp.resolve("uploads/report.txt"), "report");
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);

        Path backup = EncryptedBackupApp.createBackup(temp, temp.resolve("backups"), key);
        byte[] encrypted = Files.readAllBytes(backup);
        assertEquals("TLSCHAT1", new String(encrypted, 0, 8, StandardCharsets.US_ASCII));

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, encrypted, 8, 12));
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(cipher.doFinal(encrypted, 20, encrypted.length - 20)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertTrue(names.contains("tls-chat.db"));
        assertTrue(names.contains("uploads/report.txt"));
    }
}
