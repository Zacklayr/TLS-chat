package kz.zacklayr.tlschat;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates an AES-256-GCM encrypted migration backup outside the application process. */
public final class EncryptedBackupApp {
    private static final byte[] MAGIC = "TLSCHAT1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;

    private EncryptedBackupApp() {
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(System.getenv().getOrDefault("BACKUP_SOURCE_DIR", "."))
                .toAbsolutePath().normalize();
        Path output = Path.of(System.getenv().getOrDefault("BACKUP_OUTPUT_DIR", "backups"))
                .toAbsolutePath().normalize();
        String encodedKey = System.getenv("BACKUP_ENCRYPTION_KEY");
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("BACKUP_ENCRYPTION_KEY must contain a base64 32-byte key");
        }
        byte[] key = Base64.getDecoder().decode(encodedKey);
        String suffix = args.length > 0 && "--stdin".equals(args[0]) ? ".backup.enc" : ".zip.enc";
        Path encrypted = output.resolve("tls-chat-"
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + suffix);
        if (args.length > 0 && "--stdin".equals(args[0])) {
            validateKey(key);
            Files.createDirectories(output);
            encrypt(System.in, encrypted, key);
            System.out.println(encrypted);
            return;
        }
        System.out.println(createBackup(source, output, key));
    }

    static Path createBackup(Path source, Path output, byte[] key) throws Exception {
        validateKey(key);
        Files.createDirectories(output);
        Path encrypted = output.resolve("tls-chat-"
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + ".zip.enc");
        Path temporaryArchive = Files.createTempFile(output, "tls-chat-", ".zip.tmp");
        try {
            createArchive(source, temporaryArchive);
            encrypt(temporaryArchive, encrypted, key);
            return encrypted;
        } finally {
            Files.deleteIfExists(temporaryArchive);
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Backup key must contain exactly 32 bytes");
        }
    }

    private static void createArchive(Path source, Path archive) throws IOException {
        Path realSource = source.toRealPath();
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file))) {
            addIfExists(realSource, realSource.resolve("tls-chat.db"), zip);
            addDirectory(realSource, realSource.resolve("uploads"), zip);
            addDirectory(realSource, realSource.resolve("photos"), zip);
        }
    }

    private static void addDirectory(Path source, Path directory, ZipOutputStream zip) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                addIfExists(source, path, zip);
            }
        }
    }

    private static void addIfExists(Path source, Path path, ZipOutputStream zip) throws IOException {
        if (!Files.isRegularFile(path)) return;
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(source)) throw new IOException("Backup path escapes source directory");
        String name = source.relativize(realPath).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = new BufferedInputStream(Files.newInputStream(realPath))) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static void encrypt(Path input, Path output, byte[] key) throws Exception {
        try (InputStream source = new BufferedInputStream(Files.newInputStream(input))) {
            encrypt(source, output, key);
        }
    }

    private static void encrypt(InputStream source, Path output, byte[] key) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        try (OutputStream file = Files.newOutputStream(output);
             OutputStream buffered = new BufferedOutputStream(file);
             CipherOutputStream encrypted = new CipherOutputStream(buffered, cipher)) {
            buffered.write(MAGIC);
            buffered.write(nonce);
            source.transferTo(encrypted);
        }
    }
}
