package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttachmentStorageTest {
    @Test
    void localStorageRoundTripsAndRejectsTraversal() throws Exception {
        var directory = Files.createTempDirectory("tls-chat-attachments-");
        try {
            var storage = new AttachmentStorage.LocalAttachmentStorage(directory);
            byte[] content = "attachment".getBytes();
            storage.put("safe.bin", content, "application/octet-stream");
            assertArrayEquals(content, storage.get("safe.bin"));
            assertThrows(Exception.class, () -> storage.get("../outside.bin"));
            storage.delete("safe.bin");
        } finally {
            Files.deleteIfExists(directory.resolve("safe.bin"));
            Files.deleteIfExists(directory);
        }
    }
}
