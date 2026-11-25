package kz.zacklayr.tlschat;

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
import java.awt.GridLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
        JButton scheduleCall = new JButton("Schedule call");
        send.addActionListener(event -> sendMessage());
        composer.addActionListener(event -> sendMessage());
        attach.addActionListener(event -> uploadAttachment(frame));
        callHistory.addActionListener(event -> loadCallHistory());
        scheduleCall.addActionListener(event -> scheduleCall());
        status = new JLabel("Connected");
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        bottom.add(composer, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 4, 6, 0));
        actions.add(send);
        actions.add(attach);
        actions.add(callHistory);
        actions.add(scheduleCall);
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
        refreshChats();
        refreshTimer = new Timer("tls-chat-desktop-refresh", true);
        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> {
                    refreshChats();
                    if (chatList.getSelectedValue() != null) refreshMessages();
                });
            }
        }, 3000, 5000);
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
            for (int i = 0; i < chats.size(); i++) {
                if (chats.get(i).id().equals(selected)) chatList.setSelectedIndex(i);
            }
            status.setText("Connected · " + loaded.size() + " chats");
        } catch (Exception e) {
            status.setText("Connection error: " + e.getMessage());
        }
    }

    private void refreshMessages() {
        ChatItem chat = chatList.getSelectedValue();
        if (chat == null) return;
        try {
            String response = request("GET", "/api/chats/" + encode(chat.id()), null, token);
            messages.setText(formatMessages(response));
            messages.setCaretPosition(messages.getDocument().getLength());
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

    private void scheduleCall() {
        ChatItem chat = chatList.getSelectedValue();
        if (chat == null) return;
        try {
            String peerResponse = request("GET", "/api/chats/" + encode(chat.id()) + "/peer", null, token);
            String peer = jsonValue(peerResponse, "username");
            String body = "{\"chatId\":\"" + escape(chat.id()) +
                    "\",\"username\":\"" + escape(peer) +
                    "\",\"mediaType\":\"audio\",\"scheduledAt\":\"" +
                    Instant.now().plus(10, ChronoUnit.MINUTES) + "\"}";
            request("POST", "/api/calls/scheduled", body, token);
            status.setText("Call scheduled for 10 minutes from now");
        } catch (Exception e) {
            status.setText("Scheduling is available for direct chats only");
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
}
