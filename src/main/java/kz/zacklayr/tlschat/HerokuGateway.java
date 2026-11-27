package kz.zacklayr.tlschat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Routes Heroku's one external port to private HTTP and WebSocket backends. */
public final class HerokuGateway implements AutoCloseable {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private final int externalPort;
    private final int httpPort;
    private final int websocketPort;
    private final ChatServer chatServer;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ServerSocket listener;

    public HerokuGateway(int externalPort, int httpPort, int websocketPort, Path database)
            throws IOException, SQLException {
        this.externalPort = externalPort;
        this.httpPort = httpPort;
        this.websocketPort = websocketPort;
        System.setProperty("tlschat.wsPort", Integer.toString(websocketPort));
        this.chatServer = new ChatServer(httpPort, database);
    }

    public void start() throws IOException {
        chatServer.start();
        listener = new ServerSocket(externalPort, 100, InetAddress.getByName("0.0.0.0"));
        workers.submit(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    workers.submit(() -> proxy(client));
                } catch (IOException e) {
                    if (!listener.isClosed()) e.printStackTrace();
                }
            }
        });
    }

    private void proxy(Socket client) {
        try (client) {
            client.setSoTimeout(15_000);
            InputStream input = client.getInputStream();
            byte[] request = readHeaders(input);
            if (request == null) return;
            int backendPort = isWebSocketUpgrade(request) ? websocketPort : httpPort;
            try (Socket backend = new Socket(InetAddress.getLoopbackAddress(), backendPort)) {
                backend.getOutputStream().write(request);
                backend.getOutputStream().flush();
                client.setSoTimeout(0);
                Thread upstream = Thread.ofVirtual().start(() -> copy(input, backend));
                copy(backend.getInputStream(), client);
                upstream.interrupt();
            }
        } catch (IOException ignored) {
            // Client disconnects are normal for keep-alive and WebSocket connections.
        }
    }

    private static byte[] readHeaders(InputStream input) throws IOException {
        byte[] buffer = new byte[MAX_HEADER_BYTES];
        int count = 0;
        while (count < buffer.length) {
            int next = input.read();
            if (next < 0) return count == 0 ? null : java.util.Arrays.copyOf(buffer, count);
            buffer[count++] = (byte) next;
            if (count >= 4 && buffer[count - 4] == '\r' && buffer[count - 3] == '\n'
                    && buffer[count - 2] == '\r' && buffer[count - 1] == '\n') {
                return java.util.Arrays.copyOf(buffer, count);
            }
        }
        throw new IOException("HTTP headers are too large");
    }

    static boolean isWebSocketUpgrade(byte[] request) {
        String headers = new String(request, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return headers.lines().anyMatch(line -> line.trim().startsWith("upgrade:")
                && line.contains("websocket"));
    }

    private static void copy(InputStream input, Socket target) {
        try (OutputStream output = target.getOutputStream()) {
            input.transferTo(output);
            output.flush();
        } catch (IOException ignored) {
        } finally {
            try { target.shutdownOutput(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() {
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        chatServer.close();
    }

    public static void main(String[] args) throws Exception {
        int port = envInt("PORT", 8080);
        int httpPort = envInt("APP_HTTP_PORT", 18080);
        int websocketPort = envInt("APP_WS_PORT", 18081);
        Path database = Path.of(System.getenv().getOrDefault("DATABASE_PATH", "tls-chat.db"));
        HerokuGateway gateway = new HerokuGateway(port, httpPort, websocketPort, database);
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
        gateway.start();
        System.out.println("TLS Chat Heroku gateway started on port " + port
                + " (HTTP " + httpPort + ", WS " + websocketPort + ")");
        new CountDownLatch(1).await();
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
