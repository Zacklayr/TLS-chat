package kz.zacklayr.tlschat;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridLayout;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/** Minimal employee desktop client using the same authenticated REST contracts as the PWA. */
public final class DesktopClientApp {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String defaultServerUrl;
    private String serverUrl;
    private String token;
    private final DefaultListModel<ChatItem> chats = new DefaultListModel<>();
    private JList<ChatItem> chatList;
    private JTextArea messages;
    private JTextField composer;
    private JLabel status;
    private Timer refreshTimer;
    private WebSocketClient realtimeSocket;
    private boolean realtimeConnecting;
    private String activeCallId;
    private String incomingCallId;
    private final Set<String> shownNotifications = new HashSet<>();

    private DesktopClientApp(String defaultServerUrl) {
        this.defaultServerUrl = defaultServerUrl;
    }

    public static void main(String[] args) {
        String serverUrl = args.length == 0 ? "http://localhost:8080" : args[0];
        SwingUtilities.invokeLater(() -> new DesktopClientApp(serverUrl).showLogin());
    }

    private void showLogin() {
        JFrame frame = new JFrame("TLS Chat - Employee client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTextField server = new JTextField(defaultServerUrl);
        JTextField username = new JTextField();
        JPasswordField password = new JPasswordField();
        JButton login = new JButton("Login");
        form.add(new JLabel("Server URL:"));
        form.add(server);
        form.add(new JLabel("Username:"));
        form.add(username);
        form.add(new JLabel("Password:"));
        form.add(password);
        form.add(new JLabel());
        form.add(login);
        login.addActionListener(event -> {
            try {
                serverUrl = trimServerUrl(server.getText());
                token = login(username.getText().trim(), new String(password.getPassword()));
                frame.dispose();
                showDashboard();
            } catch (Exception e) {
                showError(e);
            }
        });
        frame.add(form);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private void showDashboard() {
        JFrame frame = new JFrame("TLS Chat - Employee client");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                if (refreshTimer != null) refreshTimer.cancel();
                if (realtimeSocket != null) realtimeSocket.close();
            }
        });
        chatList = new JList<>(chats);
        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) refreshMessages();
        });
        messages = new JTextArea();
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        composer = new JTextField();
        JButton send = new JButton("Send");
        JButton attach = new JButton("Attach");
        JButton callHistory = new JButton("Call history");
        JButton scheduleCall = new JButton("Schedule audio");
        JButton scheduleVideoCall = new JButton("Schedule video");
        JButton newChat = new JButton("New direct chat");
        JButton profile = new JButton("About me");
        JButton audioCall = new JButton("Audio call");
        JButton videoCall = new JButton("Video call");
        JButton acceptCall = new JButton("Accept");
        JButton endCall = new JButton("End call");
        JButton webRtcClient = new JButton("Open WebRTC client");
        send.addActionListener(event -> sendMessage());
        composer.addActionListener(event -> sendMessage());
        attach.addActionListener(event -> uploadAttachment(frame));
        callHistory.addActionListener(event -> loadCallHistory());
        scheduleCall.addActionListener(event -> scheduleCall("audio"));
        scheduleVideoCall.addActionListener(event -> scheduleCall("video"));
        newChat.addActionListener(event -> createDirectChat(frame));
        profile.addActionListener(event -> editProfile(frame));
        audioCall.addActionListener(event -> startCall("audio"));
        videoCall.addActionListener(event -> startCall("video"));
        acceptCall.addActionListener(event -> acceptIncomingCall());
        endCall.addActionListener(event -> endActiveCall());
        webRtcClient.addActionListener(event -> openWebRtcClient());
        status = new JLabel("Connected");
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        bottom.add(composer, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(0, 4, 6, 6));
        actions.add(send);
        actions.add(attach);
        actions.add(callHistory);
        actions.add(scheduleCall);
        actions.add(scheduleVideoCall);
        actions.add(newChat);
        actions.add(profile);
        actions.add(audioCall);
        actions.add(videoCall);
        actions.add(acceptCall);
        actions.add(endCall);
        actions.add(webRtcClient);
        bottom.add(actions, BorderLayout.EAST);
        JPanel right = new JPanel(new BorderLayout());
        right.add(new JScrollPane(messages), BorderLayout.CENTER);
        right.add(bottom, BorderLayout.SOUTH);
        JPanel left = new JPanel(new BorderLayout());
        left.add(new JLabel("Available chats"), BorderLayout.NORTH);
        left.add(new JScrollPane(chatList), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(240);
        frame.add(split, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(900, 600);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        connectRealtime();
        refreshChats();
        refreshTimer = new Timer("tls-chat-desktop-refresh", true);
        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> {
                    refreshChats();
                    if (chatList.getSelectedValue() != null) refreshMessages();
                    pollIncomingCalls();
                    checkNotifications();
                    if (realtimeSocket == null || !realtimeSocket.isOpen()) connectRealtime();
                });
            }

        }, 3000, 5000);
    }

    private void openWebRtcClient() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            status.setText("System browser is unavailable");
            return;
        }
        status.setText("Preparing authenticated WebRTC client...");
        Thread handoff = new Thread(() -> {
            try {
                String response = request("POST", "/api/browser-handoff", null, token);
                String code = jsonValue(response, "code");
                Desktop.getDesktop().browse(URI.create(serverUrl + "/?handoff="
                        + URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8)));
                SwingUtilities.invokeLater(() -> status.setText("Authenticated WebRTC client opened in the system browser"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> status.setText("Could not open WebRTC client: " + e.getMessage()));
            }
        }, "tls-chat-browser-handoff");
        handoff.setDaemon(true);
        handoff.start();
    }

    private void connectRealtime() {
        if (token == null || token.isBlank() || (realtimeSocket != null
                && realtimeSocket.getReadyState() != ReadyState.CLOSED) || realtimeConnecting) return;
        realtimeConnecting = true;
        Thread ticketRequest = new Thread(() -> {
            try {
                String ticketResponse = request("POST", "/api/realtime-ticket", null, token);
                String ticket = jsonValue(ticketResponse, "ticket");
                SwingUtilities.invokeLater(() -> openRealtimeSocket(ticket));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    realtimeConnecting = false;
                    status.setText("Realtime unavailable; REST fallback active");
                });
            }
        }, "tls-chat-desktop-realtime-ticket");
        ticketRequest.setDaemon(true);
        ticketRequest.start();
    }

    private void openRealtimeSocket(String ticket) {
        try {
            String websocketBase = serverUrl.replaceFirst("^https", "wss").replaceFirst("^http", "ws");
            URI endpoint = URI.create(websocketBase + "/ws/?ticket="
                    + URLEncoder.encode(ticket, java.nio.charset.StandardCharsets.UTF_8));
            realtimeSocket = new WebSocketClient(endpoint) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    SwingUtilities.invokeLater(() -> {
                        realtimeConnecting = false;
                        status.setText("Realtime connected");
                        subscribeRealtimeChats();
                    });
                }

                @Override
                public void onMessage(String message) {
                    SwingUtilities.invokeLater(() -> {
                        if (message.contains("\"type\":\"message\"")) {
                            String chatId = jsonValue(message, "chatId");
                            ChatItem selected = chatList == null ? null : chatList.getSelectedValue();
                            if (selected != null && selected.id().equals(chatId)) refreshMessages();
                        } else if (message.contains("\"type\":\"delivered\"")) {
                            status.setText("Message delivered");
                        } else if (message.contains("\"type\":\"presence\"")) {
                            refreshChats();
                        } else if (message.contains("\"type\":\"typing\"")) {
                            status.setText("Typing: " + jsonValue(message, "name"));
                        } else if (message.contains("\"type\":\"notification\"")) {
                            checkNotifications();
                        }
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    SwingUtilities.invokeLater(() -> {
                        realtimeConnecting = false;
                        status.setText("Realtime unavailable; REST fallback active");
                    });
                }

                @Override
                public void onError(Exception exception) {
                    SwingUtilities.invokeLater(() -> {
                        realtimeConnecting = false;
                        status.setText("Realtime unavailable; REST fallback active");
                    });
                }
            };
            realtimeSocket.connect();
        } catch (IllegalArgumentException ignored) {
            realtimeConnecting = false;
            status.setText("Invalid realtime server URL; REST fallback active");
        }
    }

    private void subscribeRealtimeChats() {
        if (realtimeSocket == null || !realtimeSocket.isOpen()) return;
        for (int i = 0; i < chats.size(); i++) {
            realtimeSocket.send("{\"type\":\"subscribe\",\"chatId\":\""
                    + escape(chats.get(i).id()) + "\"}");
        }
    }

    private void sendRealtimeRead(String chatId) {
        if (!"anonymous".equals(chatId) && realtimeSocket != null && realtimeSocket.isOpen()) {
            realtimeSocket.send("{\"type\":\"read\",\"chatId\":\""
                    + escape(chatId) + "\"}");
        }
    }

    private void checkNotifications() {
        try {
            for (String object : objects(request("GET", "/api/notifications", null, token))) {
                String id = jsonValue(object, "id");
                if (!shownNotifications.add(id)) continue;
                String title = jsonValue(object, "title");
                String body = jsonValue(object, "body");
                String severity = jsonValue(object, "severity");
                if ("emergency".equals(severity)) {
                    JOptionPane.showMessageDialog(null, body, "EMERGENCY: " + title,
                            JOptionPane.WARNING_MESSAGE);
                } else {
                    status.setText(title + ": " + body);
                }
                request("POST", "/api/notifications/" + encode(id) + "/read", null, token);
            }
        } catch (Exception e) {
            status.setText("Notification check failed: " + e.getMessage());
        }
    }

    private String login(String username, String password) throws Exception {
        String response = request("POST", "/api/login", "{\"username\":\"" + escape(username)
                + "\",\"password\":\"" + escape(password) + "\"}", null);
        return jsonValue(response, "token");
    }

    private void refreshChats() {
        try {
            List<ChatItem> loaded = parseChats(request("GET", "/api/chats", null, token));
            String selected = chatList == null || chatList.getSelectedValue() == null
                    ? "" : chatList.getSelectedValue().id();
            chats.clear();
            loaded.forEach(chats::addElement);
            subscribeRealtimeChats();
            for (int i = 0; i < chats.size(); i++) {
                if (chats.get(i).id().equals(selected)) chatList.setSelectedIndex(i);
            }
            status.setText("Connected · " + loaded.size() + " chats");
        } catch (Exception e) {
            status.setText("Connection error: " + e.getMessage());
        }
    }

    private void createDirectChat(JFrame parent) {
        try {
            List<UserItem> users = parseUsers(request("GET", "/api/users", null, token));
            if (users.isEmpty()) {
                status.setText("No approved employees available");
                return;
            }
            String[] labels = users.stream().map(UserItem::label).toArray(String[]::new);
            String selected = (String) JOptionPane.showInputDialog(parent, "Choose an employee:",
                    "New direct chat", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
            if (selected == null) return;
            UserItem target = users.stream().filter(user -> user.label().equals(selected)).findFirst().orElseThrow();
            String response = request("POST", "/api/chats/direct",
                    "{\"username\":\"" + escape(target.username()) + "\"}", token);
            ChatItem created = new ChatItem(jsonValue(response, "id"), jsonValue(response, "name"));
            refreshChats();
            for (int i = 0; i < chats.size(); i++) {
                if (chats.get(i).id().equals(created.id())) chatList.setSelectedIndex(i);
            }
            status.setText("Direct chat opened");
        } catch (Exception e) {
            status.setText("Could not open direct chat: " + e.getMessage());
        }
    }

    private void editProfile(JFrame parent) {
        try {
            String profile = request("GET", "/api/profile", null, token);
            String about = JOptionPane.showInputDialog(parent, "About me:", jsonValue(profile, "about"));
            if (about == null) return;
            String[] presenceValues = {"online", "away", "busy", "offline"};
            String currentPresence = jsonValue(profile, "presence");
            String presence = (String) JOptionPane.showInputDialog(parent, "Presence:", "Profile",
                    JOptionPane.PLAIN_MESSAGE, null, presenceValues,
                    java.util.Arrays.stream(presenceValues).anyMatch(currentPresence::equals)
                            ? currentPresence : presenceValues[0]);
            if (presence == null) return;
            request("PUT", "/api/profile", "{\"about\":\"" + escape(about)
                    + "\",\"presence\":\"" + escape(presence) + "\"}", token);
            status.setText("Profile saved");
        } catch (Exception e) {
            status.setText("Could not save profile: " + e.getMessage());
        }
    }

    private void refreshMessages() {
        ChatItem chat = chatList.getSelectedValue();
        if (chat == null) return;
        try {
            String response = request("GET", "/api/chats/" + encode(chat.id()), null, token);
            messages.setText(formatMessages(response));
            messages.setCaretPosition(messages.getDocument().getLength());
            sendRealtimeRead(chat.id());
        } catch (Exception e) {
            status.setText("Could not load messages: " + e.getMessage());
        }
    }

    private void sendMessage() {
        ChatItem chat = chatList.getSelectedValue();
        String text = composer.getText().trim();
        if (chat == null || text.isBlank()) return;
        try {
            request("POST", "/api/chats/" + encode(chat.id()) + "/messages",
                    "{\"text\":\"" + escape(text) + "\"}", token);
            composer.setText("");
            refreshMessages();
        } catch (Exception e) {
            showError(e);
        }
    }

    private void uploadAttachment(JFrame parent) {
        ChatItem chat = chatList.getSelectedValue();
        if (chat == null) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        try {
            long size = Files.size(file);
            if (size > 25L * 1024 * 1024) throw new IllegalArgumentException("Maximum file size is 25 MB");
            byte[] bytes = Files.readAllBytes(file);
            String contentType = Files.probeContentType(file);
            if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
            String response = requestBytes("POST", "/api/files?chatId=" + encode(chat.id()), bytes,
                    token, file.getFileName().toString(), contentType);
            String url = jsonValue(response, "url");
            String name = jsonValue(response, "name");
            request("POST", "/api/chats/" + encode(chat.id()) + "/messages",
                    "{\"text\":\"[file:" + escape(url) + "|" + escape(name) + "]\"}", token);
            status.setText("Uploaded: " + name);
        } catch (Exception e) {
            showError(e);
        }
    }

    private void loadCallHistory() {
        try {
            String response = request("GET", "/api/calls/history?limit=20", null, token);
            messages.setText(formatCalls(response));
        } catch (Exception e) {
            showError(e);
        }
    }

    private void scheduleCall(String mediaType) {
        ChatItem chat = chatList.getSelectedValue();
        if (chat == null) return;
        String delayText = JOptionPane.showInputDialog(null, "Minutes from now:", "10");
        if (delayText == null) return;
        long delayMinutes;
        try {
            delayMinutes = Long.parseLong(delayText.trim());
            if (delayMinutes < 1 || delayMinutes > 10080) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            status.setText("Enter a delay from 1 to 10080 minutes");
            return;
        }
        try {
            String peerResponse = request("GET", "/api/chats/" + encode(chat.id()) + "/peer", null, token);
            String peer = jsonValue(peerResponse, "username");
            String body = "{\"chatId\":\"" + escape(chat.id()) +
                    "\",\"username\":\"" + escape(peer) +
                    "\",\"mediaType\":\"" + escape(mediaType) + "\",\"scheduledAt\":\"" +
                    Instant.now().plus(delayMinutes, ChronoUnit.MINUTES) + "\"}";
            request("POST", "/api/calls/scheduled", body, token);
            status.setText(mediaType + " call scheduled for " + delayMinutes + " minutes from now");
        } catch (Exception e) {
            status.setText("Scheduling is available for direct chats only");
        }
    }

    private void startCall(String mediaType) {
        ChatItem chat = chatList.getSelectedValue();
        if (chat == null) return;
        try {
            String peer = jsonValue(request("GET", "/api/chats/" + encode(chat.id()) + "/peer", null, token), "username");
            String response = request("POST", "/api/calls", "{\"chatId\":\"" + escape(chat.id())
                    + "\",\"username\":\"" + escape(peer) + "\",\"mediaType\":\""
                    + escape(mediaType) + "\"}", token);
            activeCallId = jsonValue(response, "id");
            status.setText("Call ringing (" + mediaType + "); native media capture requires the WebRTC client");
        } catch (Exception e) {
            showError(e);
        }
    }

    private void pollIncomingCalls() {
        try {
            String response = request("GET", "/api/calls/incoming", null, token);
            List<String> calls = objects(response);
            if (!calls.isEmpty()) {
                incomingCallId = jsonValue(calls.get(0), "id");
                status.setText("Incoming " + jsonValue(calls.get(0), "mediaType") + " call — press Accept");
            }
        } catch (Exception ignored) {
        }
    }

    private void acceptIncomingCall() {
        if (incomingCallId == null) return;
        try {
            request("POST", "/api/calls/" + encode(incomingCallId) + "/events",
                    "{\"type\":\"accept\",\"payload\":\"\"}", token);
            activeCallId = incomingCallId;
            incomingCallId = null;
            status.setText("Call accepted; native media capture requires the WebRTC client");
        } catch (Exception e) {
            showError(e);
        }
    }

    private void endActiveCall() {
        if (activeCallId == null) return;
        try {
            request("POST", "/api/calls/" + encode(activeCallId) + "/events",
                    "{\"type\":\"end\",\"payload\":\"\"}", token);
            activeCallId = null;
            status.setText("Call ended");
        } catch (Exception e) {
            showError(e);
        }
    }

    private String request(String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serverUrl + path))
                .header("Content-Type", "application/json");
        if (authorization != null) builder.header("Authorization", "Bearer " + authorization);
        HttpRequest request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String requestBytes(String method, String path, byte[] body, String authorization,
                                String filename, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serverUrl + path))
                .header("Content-Type", contentType)
                .header("X-Filename", filename);
        if (authorization != null) builder.header("Authorization", "Bearer " + authorization);
        HttpResponse<String> response = http.send(builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static List<ChatItem> parseChats(String json) {
        List<ChatItem> result = new ArrayList<>();
        for (String object : objects(json)) {
            result.add(new ChatItem(jsonValue(object, "id"), jsonValue(object, "name")));
        }
        return result;
    }

    private static List<UserItem> parseUsers(String json) {
        List<UserItem> result = new ArrayList<>();
        for (String object : objects(json)) {
            result.add(new UserItem(jsonValue(object, "username"), jsonValue(object, "displayName"),
                    jsonValue(object, "profession"), jsonValue(object, "workplace")));
        }
        return result;
    }

    private static String formatMessages(String json) {
        StringBuilder result = new StringBuilder();
        for (String object : objects(json)) {
            result.append(jsonValue(object, "name")).append(": ")
                    .append(jsonValue(object, "text")).append('\n');
        }
        return result.toString();
    }

    private static String formatCalls(String json) {
        StringBuilder result = new StringBuilder("Call history:\n");
        for (String object : objects(json)) {
            result.append(jsonValue(object, "mediaType")).append(" · ")
                    .append(jsonValue(object, "status")).append(" · ")
                    .append(jsonValue(object, "createdAt")).append('\n');
        }
        return result.toString();
    }

    private static List<String> objects(String json) {
        List<String> result = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}' && --depth == 0) result.add(json.substring(start, i + 1));
        }
        return result;
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalStateException("Missing response field: " + key);
        start += marker.length();
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { value.append(c); escaped = false; }
            else if (c == '\\') escaped = true;
            else if (c == '"') return value.toString();
            else value.append(c);
        }
        throw new IllegalStateException("Invalid JSON response");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String trimServerUrl(String value) {
        String result = value.trim();
        if (!result.contains("://")) result = "https://" + result;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            throw new IllegalArgumentException("Server URL must use HTTP or HTTPS");
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void showError(Exception e) {
        JOptionPane.showMessageDialog(null, e.getMessage(), "TLS Chat error", JOptionPane.ERROR_MESSAGE);
    }

    private record ChatItem(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record UserItem(String username, String displayName, String profession, String workplace) {
        private String label() {
            return displayName + " · " + profession + " · " + workplace + " (" + username + ")";
        }
    }
}
