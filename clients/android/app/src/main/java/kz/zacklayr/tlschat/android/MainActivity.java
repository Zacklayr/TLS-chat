package kz.zacklayr.tlschat.android;

import android.app.Activity;
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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal native Android client using the shared TLS-Chat REST API. */
public final class MainActivity extends Activity {
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
        LinearLayout composerRow = new LinearLayout(this);
        composerRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        composerRow.addView(send, new LinearLayout.LayoutParams(-2, -2));
        root.addView(status);
        root.addView(chatList, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(composerRow);
        setContentView(root);
        chatList.setOnItemClickListener((parent, view, position, id) -> {
            selectedChat = chatItems.get(position);
            loadMessages();
        });
        send.setOnClickListener(view -> sendMessage());
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
