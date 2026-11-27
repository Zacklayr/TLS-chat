package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HerokuGatewayTest {
    @Test
    void routesOnlyWebSocketUpgradeRequestsToRealtimeBackend() {
        assertTrue(HerokuGateway.isWebSocketUpgrade(("GET /ws/ HTTP/1.1\r\n"
                + "Host: localhost\r\nUpgrade: websocket\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(HerokuGateway.isWebSocketUpgrade(("GET /health HTTP/1.1\r\n"
                + "Host: localhost\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)));
    }
}
