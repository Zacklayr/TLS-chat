package kz.zacklayr.tlschat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Issues short-lived, single-use tickets for browser WebSocket authentication. */
public final class WebSocketTicketStore {
    private static final long TICKET_LIFETIME_SECONDS = 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String issue(String username) {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new Ticket(username, now.plusSeconds(TICKET_LIFETIME_SECONDS)));
        return ticket;
    }

    public String consume(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        Ticket issued = tickets.remove(ticket);
        return issued != null && issued.expiresAt().isAfter(Instant.now()) ? issued.username() : null;
    }

    public static long lifetimeSeconds() {
        return TICKET_LIFETIME_SECONDS;
    }

    private record Ticket(String username, Instant expiresAt) { }
}
