package kz.zacklayr.tlschat.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal native Android client using the shared TLS-Chat REST API. */
public final class MainActivity extends Activity {
    private static final int FILE_REQUEST = 42;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ChatItem> chatItems = new ArrayList<>();
    private String token;
    private String server;
    private TextView status;
    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText composer;
    private ListView chatList;
    private TextView messages;
    private ChatItem selectedChat;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        showLogin();
    }

    private void showLogin() {
        LinearLayout form = column();
        serverInput = input("https://your-tls-chat-host", false);
        usernameInput = input("Username", false);
        passwordInput = input("Password", true);
        Button login = new Button(this);
        login.setText("Login");
        status = new TextView(this);
        form.addView(serverInput);
        form.addView(usernameInput);
        form.addView(passwordInput);
        form.addView(login);
        form.addView(status);
        login.setOnClickListener(view -> login());
        setContentView(form);
    }

    private void login() {
        server = serverInput.getText().toString().trim().replaceAll("/+$", "");
        if (!server.startsWith("https://")) {
            setStatus("Use an HTTPS server URL");
            return;
        }
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        request("POST", "/api/login", "{\"username\":\"" + escape(username)
                + "\",\"password\":\"" + escape(password) + "\"}", response -> {
                    try {
                        token = new JSONObject(response).getString("token");
                        showMessenger();
                        loadChats();
                    } catch (Exception e) {
                        setStatus(e.getMessage());
                    }
                });
    }

    private void showMessenger() {
        LinearLayout root = column();
        status = new TextView(this);
        chatList = new ListView(this);
        messages = new TextView(this);
        messages.setPadding(12, 12, 12, 12);
        composer = input("Message", false);
        Button send = new Button(this);
        send.setText("Send");
        Button attach = new Button(this);
        attach.setText("Attach");
        Button callHistory = new Button(this);
        callHistory.setText("Call history");
        Button scheduleCall = new Button(this);
        scheduleCall.setText("Schedule call");
        LinearLayout composerRow = new LinearLayout(this);
        composerRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        composerRow.addView(attach, new LinearLayout.LayoutParams(-2, -2));
        composerRow.addView(send, new LinearLayout.LayoutParams(-2, -2));
        root.addView(status);
        root.addView(chatList, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(composerRow);
        root.addView(callHistory);
        root.addView(scheduleCall);
        setContentView(root);
        chatList.setOnItemClickListener((parent, view, position, id) -> {
            selectedChat = chatItems.get(position);
            loadMessages();
        });
        send.setOnClickListener(view -> sendMessage());
        attach.setOnClickListener(view -> chooseAttachment());
        callHistory.setOnClickListener(view -> loadCallHistory());
        scheduleCall.setOnClickListener(view -> scheduleCall());
    }

    private void loadChats() {
        request("GET", "/api/chats", null, response -> {
            try {
                JSONArray array = new JSONArray(response);
                chatItems.clear();
                List<String> labels = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    ChatItem chat = new ChatItem(item.getString("id"), item.getString("name"));
                    chatItems.add(chat);
                    labels.add(chat.name);
                }
                chatList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
                setStatus("Connected · " + labels.size() + " chats");
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void loadMessages() {
        if (selectedChat == null) return;
        request("GET", "/api/chats/" + selectedChat.id, null, response -> {
            try {
                JSONArray array = new JSONArray(response);
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject message = array.getJSONObject(i);
                    text.append(message.optString("name", message.optString("from")))
                            .append(": ").append(message.optString("text")).append('\n');
                }
                messages.setText(text.toString());
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void sendMessage() {
        if (selectedChat == null || composer.getText().toString().trim().isEmpty()) return;
        String text = composer.getText().toString();
        request("POST", "/api/chats/" + selectedChat.id + "/messages",
                "{\"text\":\"" + escape(text) + "\"}", response -> {
                    composer.setText("");
                    loadMessages();
                });
    }

    private void chooseAttachment() {
        if (selectedChat == null) {
            setStatus("Select a chat first");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        executor.execute(() -> {
            try {
                byte[] bytes;
                try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (input == null) throw new IllegalStateException("Could not open file");
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                        if (output.size() > 25 * 1024 * 1024) throw new IllegalArgumentException("Maximum file size is 25 MB");
                    }
                    bytes = output.toByteArray();
                }
                String name = uri.getLastPathSegment() == null ? "attachment" : uri.getLastPathSegment();
                String type = getContentResolver().getType(uri);
                if (type == null || type.isBlank()) type = "application/octet-stream";
                String response = requestBytes("POST", "/api/files?chatId=" + selectedChat.id,
                        bytes, name, type);
                JSONObject attachment = new JSONObject(response);
                String uploadedName = attachment.optString("name", name);
                String uploadedUrl = attachment.optString("url", "");
                request("POST", "/api/chats/" + selectedChat.id + "/messages",
                        "{\"text\":\"[file:" + escape(uploadedUrl) + "|" + escape(uploadedName) + "]\"}",
                        ignored -> setStatus("Uploaded: " + uploadedName));
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void request(String method, String path, String body, java.util.function.Consumer<String> success) {
        executor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Content-Type", "application/json");
                if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = connection.getResponseCode();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = reader.lines().reduce("", (left, right) -> left + right);
                    if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + response);
                    main.post(() -> success.accept(response));
                }
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private String requestBytes(String method, String path, byte[] body, String filename, String contentType)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("X-Filename", filename);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int code = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
            String response = reader.lines().reduce("", (left, right) -> left + right);
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + response);
            return response;
        }
    }

    private void loadCallHistory() {
        request("GET", "/api/calls/history?limit=20", null, response -> {
            try {
                JSONArray calls = new JSONArray(response);
                StringBuilder text = new StringBuilder("Call history:\n");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    text.append(call.optString("mediaType")).append(" · ")
                            .append(call.optString("status")).append(" · ")
                            .append(call.optString("createdAt")).append('\n');
                }
                messages.setText(text.toString());
                setStatus("Call history loaded");
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void scheduleCall() {
        if (selectedChat == null) {
            setStatus("Select a direct chat first");
            return;
        }
        request("GET", "/api/chats/" + selectedChat.id + "/peer", null, peerResponse -> {
            try {
                String peer = new JSONObject(peerResponse).getString("username");
                String body = "{\"chatId\":\"" + escape(selectedChat.id) +
                        "\",\"username\":\"" + escape(peer) +
                        "\",\"mediaType\":\"audio\",\"scheduledAt\":\"" +
                        Instant.now().plus(10, ChronoUnit.MINUTES) + "\"}";
                request("POST", "/api/calls/scheduled", body,
                        response -> setStatus("Call scheduled for 10 minutes from now"));
            } catch (Exception e) {
                setStatus("Scheduling is available for direct chats only");
            }
        });
    }

    private void setStatus(String text) {
        main.post(() -> { if (status != null) status.setText(text == null ? "Unknown error" : text); });
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        return layout;
    }

    private EditText input(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private record ChatItem(String id, String name) { }
}
