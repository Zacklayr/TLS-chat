package kz.zacklayr.tlschat;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

interface AttachmentStorage extends AutoCloseable {
    void put(String key, byte[] data, String contentType) throws IOException;

    byte[] get(String key) throws IOException;

    void delete(String key) throws IOException;

    @Override
    default void close() {
    }

    static AttachmentStorage fromEnvironment() throws IOException {
        String mode = System.getenv().getOrDefault("FILE_STORAGE_MODE", "local").trim().toLowerCase();
        if ("s3".equals(mode)) {
            String bucket = System.getenv().getOrDefault("S3_BUCKET", "").trim();
            if (bucket.isBlank()) {
                throw new IOException("S3_BUCKET is required when FILE_STORAGE_MODE=s3");
            }
            String region = System.getenv().getOrDefault("S3_REGION", "us-east-1").trim();
            String prefix = System.getenv().getOrDefault("S3_PREFIX", "tls-chat/").trim();
            String endpoint = System.getenv().getOrDefault("S3_ENDPOINT", "").trim();
            S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
            if (!endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
                if (System.getenv("AWS_ACCESS_KEY_ID") == null) {
                    throw new IOException("AWS_ACCESS_KEY_ID is required with S3_ENDPOINT");
                }
            }
            return new S3AttachmentStorage(builder.build(), bucket, prefix);
        }
        if (!"local".equals(mode)) {
            throw new IOException("FILE_STORAGE_MODE must be local or s3");
        }
        Path directory = Path.of(System.getenv().getOrDefault("FILE_STORAGE_DIR", "uploads"))
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return new LocalAttachmentStorage(directory);
    }

    final class LocalAttachmentStorage implements AttachmentStorage {
        private final Path directory;

        LocalAttachmentStorage(Path directory) {
            this.directory = directory;
        }

        @Override
        public void put(String key, byte[] data, String contentType) throws IOException {
            Path path = resolve(key);
            Files.write(path, data);
        }

        @Override
        public byte[] get(String key) throws IOException {
            return Files.readAllBytes(resolve(key));
        }

        @Override
        public void delete(String key) throws IOException {
            Files.deleteIfExists(resolve(key));
        }

        private Path resolve(String key) throws IOException {
            Path path = directory.resolve(key).normalize();
            if (!path.getParent().equals(directory)) {
                throw new IOException("Invalid attachment key");
            }
            return path;
        }
    }

    final class S3AttachmentStorage implements AttachmentStorage {
        private final S3Client client;
        private final String bucket;
        private final String prefix;

        S3AttachmentStorage(S3Client client, String bucket, String prefix) {
            this.client = client;
            this.bucket = bucket;
            this.prefix = prefix.isBlank() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        }

        @Override
        public void put(String key, byte[] data, String contentType) throws IOException {
            try {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(prefix + key)
                        .contentType(contentType).contentLength((long) data.length).build(), RequestBody.fromBytes(data));
            } catch (S3Exception e) {
                throw new IOException("Could not store attachment in S3", e);
            }
        }

        @Override
        public byte[] get(String key) throws IOException {
            try {
                return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build())
                        .asByteArray();
            } catch (S3Exception e) {
                throw new IOException("Attachment not found in S3", e);
            }
        }

        @Override
        public void delete(String key) throws IOException {
            try {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
            } catch (S3Exception e) {
                throw new IOException("Could not delete attachment from S3", e);
            }
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
