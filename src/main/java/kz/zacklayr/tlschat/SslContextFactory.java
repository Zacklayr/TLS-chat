package kz.zacklayr.tlschat;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

public final class SslContextFactory {
    private static final String TLS_PROTOCOL = "TLSv1.3";
    private static final String KEYSTORE_TYPE = "PKCS12";

    private SslContextFactory() {
    }

    public static SSLContext serverContext(Path keyStorePath, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = loadKeyStore(keyStorePath, password);
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);

        SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    public static SSLContext clientContext(Path trustStorePath, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore trustStore = loadKeyStore(trustStorePath, password);
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);

        SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadKeyStore(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        if (path == null || password == null) {
            throw new IllegalArgumentException("Keystore path and password are required");
        }
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, password);
        }
        return keyStore;
    }
}
