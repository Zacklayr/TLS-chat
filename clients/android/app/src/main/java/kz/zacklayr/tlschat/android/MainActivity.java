package kz.zacklayr.tlschat.android;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
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
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal native Android client using the shared TLS-Chat REST API. */
public final class MainActivity extends Activity {
    private static final int FILE_REQUEST = 42;
    private static final int WEBRTC_PERMISSION_REQUEST = 43;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ChatItem> chatItems = new ArrayList<>();
    private String token;
    private String webUserJson;
    private String server;
    private TextView status;
    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText composer;
    private ListView chatList;
    private TextView messages;
    private ChatItem selectedChat;
    private String activeCallId;
    private String incomingCallId;
    private WebSocketClient realtimeSocket;
    private boolean realtimeConnecting;
    private WebView webRtcView;
    private final Set<String> shownNotifications = new HashSet<>();

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
        Button register = new Button(this);
        register.setText("Submit registration");
        Button admin = new Button(this);
        admin.setText("Admin panel");
        status = new TextView(this);
        form.addView(serverInput);
        form.addView(usernameInput);
        form.addView(passwordInput);
        form.addView(login);
        form.addView(register);
        form.addView(admin);
        form.addView(status);
        login.setOnClickListener(view -> login());
        register.setOnClickListener(view -> showRegistration());
        admin.setOnClickListener(view -> showAdminLogin());
        setContentView(form);
    }

    private void showRegistration() {
        LinearLayout form = column();
        EditText fullName = input("Full name", false);
        EditText profession = input("Profession", false);
        EditText workplace = input("Workplace", false);
        EditText region = input("Region", false);
        EditText phone = input("Phone", false);
        Button submit = new Button(this);
        submit.setText("Send registration");
        Button back = new Button(this);
        back.setText("Back to login");
        TextView result = new TextView(this);
        form.addView(fullName);
        form.addView(profession);
        form.addView(workplace);
        form.addView(region);
        form.addView(phone);
        form.addView(submit);
        form.addView(back);
        form.addView(result);
        submit.setOnClickListener(view -> {
            String registrationServer = serverInput == null ? "" : serverInput.getText().toString().trim().replaceAll("/+$", "");
            if (!registrationServer.startsWith("https://")) {
                result.setText("Enter an HTTPS server URL on the login screen first");
                return;
            }
            server = registrationServer;
            String body = "{\"fullName\":\"" + escape(fullName.getText().toString())
                    + "\",\"profession\":\"" + escape(profession.getText().toString())
                    + "\",\"workplace\":\"" + escape(workplace.getText().toString())
                    + "\",\"region\":\"" + escape(region.getText().toString())
                    + "\",\"phone\":\"" + escape(phone.getText().toString()) + "\"}";
            request("POST", "/api/registration", body,
                    response -> result.setText("Registration submitted. The registrar will review it."));
        });
        back.setOnClickListener(view -> showLogin());
        setContentView(form);
    }

    private void showAdminLogin() {
        LinearLayout form = column();
        EditText adminUsername = input("Admin username", false);
        EditText adminPassword = input("Admin password", true);
        Button signIn = new Button(this);
        signIn.setText("Open admin panel");
        Button back = new Button(this);
        back.setText("Back to login");
        TextView result = new TextView(this);
        form.addView(adminUsername);
        form.addView(adminPassword);
        form.addView(signIn);
        form.addView(back);
        form.addView(result);
        signIn.setOnClickListener(view -> {
            server = serverInput == null ? "" : serverInput.getText().toString().trim().replaceAll("/+$", "");
            if (!server.startsWith("https://")) {
                result.setText("Use an HTTPS server URL");
                return;
            }
            request("POST", "/api/admin/login", "{\"username\":\"" + escape(adminUsername.getText().toString())
                    + "\",\"password\":\"" + escape(adminPassword.getText().toString()) + "\"}", response -> {
                try {
                    token = new JSONObject(response).getString("token");
                    showAdminPanel();
                } catch (Exception e) {
                    result.setText(e.getMessage());
                }
            });
        });
        back.setOnClickListener(view -> showLogin());
        setContentView(form);
    }

    private void showAdminPanel() {
        LinearLayout root = column();
        TextView output = new TextView(this);
        output.setPadding(12, 12, 12, 12);
        Button registrations = new Button(this);
        registrations.setText("Pending registrations");
        Button users = new Button(this);
        users.setText("Users and access");
        Button logout = new Button(this);
        logout.setText("Admin logout");
        root.addView(registrations);
        root.addView(users);
        root.addView(logout);
        root.addView(output, new LinearLayout.LayoutParams(-1, 0, 1));
        registrations.setOnClickListener(view -> loadAdminRegistrations(output));
        users.setOnClickListener(view -> loadAdminUsers(output));
        logout.setOnClickListener(view -> {
            token = null;
            showLogin();
        });
        setContentView(root);
        loadAdminRegistrations(output);
    }

    private void loadAdminRegistrations(TextView output) {
        request("GET", "/api/admin/registrations", null, response -> {
            try {
                JSONArray registrations = new JSONArray(response);
                StringBuilder text = new StringBuilder("Pending registrations:\n");
                for (int i = 0; i < registrations.length(); i++) {
                    JSONObject item = registrations.getJSONObject(i);
                    text.append(i + 1).append(". ").append(item.optString("fullName"))
                            .append(" | ").append(item.optString("profession"))
                            .append(" | ").append(item.optString("status")).append('\n');
                }
                output.setText(registrations.length() == 0 ? "No pending registrations" : text.toString());
                output.setOnClickListener(view -> {
                    if (registrations.length() > 0) showRegistrationDecision(registrations, output);
                });
            } catch (Exception e) {
                output.setText(e.getMessage());
            }
        });
    }

    private void showRegistrationDecision(JSONArray registrations, TextView output) {
        String[] labels = new String[registrations.length()];
        for (int i = 0; i < registrations.length(); i++) {
            JSONObject item = registrations.optJSONObject(i);
            labels[i] = item == null ? "Unknown" : item.optString("fullName");
        }
        new AlertDialog.Builder(this).setTitle("Select registration")
                .setItems(labels, (dialog, which) -> decideRegistration(registrations.optJSONObject(which), output))
                .show();
    }

    private void decideRegistration(JSONObject registration, TextView output) {
        if (registration == null) return;
        EditText username = input("Username (for approval)", false);
        EditText password = input("Temporary password (for approval)", true);
        LinearLayout form = column();
        form.addView(username);
        form.addView(password);
        new AlertDialog.Builder(this).setTitle("Approve or reject")
                .setView(form)
                .setNegativeButton("Reject", (dialog, which) -> submitRegistrationDecision(registration, "REJECTED", "", "", output))
                .setPositiveButton("Approve", (dialog, which) -> submitRegistrationDecision(
                        registration, "APPROVED", username.getText().toString(), password.getText().toString(), output))
                .show();
    }

    private void submitRegistrationDecision(JSONObject registration, String decision, String username,
                                            String password, TextView output) {
        String id = registration.optString("id");
        String body = "{\"status\":\"" + decision + "\",\"username\":\"" + escape(username)
                + "\",\"password\":\"" + escape(password) + "\"}";
        request("POST", "/api/admin/registrations/" + Uri.encode(id), body,
                response -> {
                    output.setText("Registration updated: " + decision);
                    loadAdminRegistrations(output);
                });
    }

    private void loadAdminUsers(TextView output) {
        request("GET", "/api/admin/users", null, response -> {
            try {
                JSONArray users = new JSONArray(response);
                StringBuilder text = new StringBuilder("Users:\n");
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.getJSONObject(i);
                    text.append(i + 1).append(". ").append(user.optString("displayName"))
                            .append(" (").append(user.optString("username")).append(")")
                            .append(" | ").append(user.optString("status"))
                            .append(" | ").append(user.optString("permissions")).append('\n');
                }
                output.setText(text.toString());
                output.setOnClickListener(view -> {
                    if (users.length() > 0) showUserAccessEditor(users, output);
                });
            } catch (Exception e) {
                output.setText(e.getMessage());
            }
        });
    }

    private void showUserAccessEditor(JSONArray users, TextView output) {
        String[] labels = new String[users.length()];
        for (int i = 0; i < users.length(); i++) labels[i] = users.optJSONObject(i).optString("displayName");
        new AlertDialog.Builder(this).setTitle("Select user")
                .setItems(labels, (dialog, which) -> {
                    JSONObject user = users.optJSONObject(which);
                    if (user == null) return;
                    EditText access = input("Status: ACTIVE or SUSPENDED", false);
                    access.setText(user.optString("status"));
                    EditText permissions = input("Permissions, comma-separated", false);
                    permissions.setText(user.optString("permissions"));
                    LinearLayout form = column();
                    form.addView(access);
                    form.addView(permissions);
                    new AlertDialog.Builder(this).setTitle("Edit user access").setView(form)
                            .setPositiveButton("Save", (d, w) -> {
                                String body = "{\"status\":\"" + escape(access.getText().toString())
                                        + "\",\"permissions\":\"" + escape(permissions.getText().toString()) + "\"}";
                                request("POST", "/api/admin/users/" + Uri.encode(user.optString("username")), body,
                                        saved -> { output.setText("User access updated"); loadAdminUsers(output); });
                            }).setNegativeButton("Cancel", null).show();
                }).show();
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
                        webUserJson = response;
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
        scheduleCall.setText("Schedule audio");
        Button scheduleVideoCall = new Button(this);
        scheduleVideoCall.setText("Schedule video");
        Button newChat = new Button(this);
        newChat.setText("New direct chat");
        Button profile = new Button(this);
        profile.setText("About me");
        Button audioCall = new Button(this);
        audioCall.setText("Audio call");
        Button videoCall = new Button(this);
        videoCall.setText("Video call");
        Button checkIncoming = new Button(this);
        checkIncoming.setText("Check incoming");
        Button acceptCall = new Button(this);
        acceptCall.setText("Accept");
        Button endCall = new Button(this);
        endCall.setText("End call");
        Button webRtcClient = new Button(this);
        webRtcClient.setText("Open WebRTC client");
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
        root.addView(scheduleVideoCall);
        root.addView(newChat);
        root.addView(profile);
        root.addView(audioCall);
        root.addView(videoCall);
        root.addView(checkIncoming);
        root.addView(acceptCall);
        root.addView(endCall);
        root.addView(webRtcClient);
        setContentView(root);
        loadNotifications();
        scheduleNotificationPoll();
        chatList.setOnItemClickListener((parent, view, position, id) -> {
            selectedChat = chatItems.get(position);
            loadMessages();
        });
        send.setOnClickListener(view -> sendMessage());
        attach.setOnClickListener(view -> chooseAttachment());
        callHistory.setOnClickListener(view -> loadCallHistory());
        scheduleCall.setOnClickListener(view -> scheduleCall("audio"));
        scheduleVideoCall.setOnClickListener(view -> scheduleCall("video"));
        newChat.setOnClickListener(view -> chooseDirectChat());
        profile.setOnClickListener(view -> editProfile());
        audioCall.setOnClickListener(view -> startCall("audio"));
        videoCall.setOnClickListener(view -> startCall("video"));
        checkIncoming.setOnClickListener(view -> checkIncomingCalls());
        acceptCall.setOnClickListener(view -> acceptIncomingCall());
        endCall.setOnClickListener(view -> endActiveCall());
        webRtcClient.setOnClickListener(view -> openWebRtcClient());
        connectRealtime();
    }

    private void openWebRtcClient() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    WEBRTC_PERMISSION_REQUEST);
            return;
        }
        showWebRtcClient();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WEBRTC_PERMISSION_REQUEST) return;
        if (grantResults.length == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            showWebRtcClient();
        } else {
            setStatus("Camera and microphone permissions are required for calls");
        }
    }

    private void showWebRtcClient() {
        WebView webView = new WebView(this);
        webRtcView = webView;
        final boolean[] sessionInjected = {false};
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (sessionInjected[0] || token == null || webUserJson == null) return;
                sessionInjected[0] = true;
                String script = "localStorage.setItem('tlsChatToken'," + JSONObject.quote(token)
                        + ");localStorage.setItem('tlsChatUser'," + JSONObject.quote(webUserJson) + ");";
                view.evaluateJavascript(script, ignored -> view.reload());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        request.deny();
                    }
                });
            }
        });
        setContentView(webView);
        webView.loadUrl(server + "/");
    }

    @Override
    public void onBackPressed() {
        if (webRtcView != null) {
            webRtcView.destroy();
            webRtcView = null;
            if (realtimeSocket != null) {
                realtimeSocket.close();
                realtimeSocket = null;
            }
            showMessenger();
            return;
        }
        super.onBackPressed();
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
                subscribeRealtimeChats();
                setStatus("Connected · " + labels.size() + " chats");
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void scheduleNotificationPoll() {
        main.postDelayed(() -> {
            if (token != null) {
                if (realtimeSocket == null || realtimeSocket.getReadyState() == ReadyState.CLOSED) {
                    connectRealtime();
                }
                loadNotifications();
                scheduleNotificationPoll();
            }
        }, 10000);
    }

    private void loadNotifications() {
        request("GET", "/api/notifications", null, response -> {
            try {
                JSONArray notifications = new JSONArray(response);
                for (int i = 0; i < notifications.length(); i++) {
                    JSONObject notification = notifications.getJSONObject(i);
                    String id = notification.getString("id");
                    if (!shownNotifications.add(id)) continue;
                    String title = notification.optString("title");
                    String body = notification.optString("body");
                    String severity = notification.optString("severity");
                    if ("emergency".equals(severity)) {
                        new AlertDialog.Builder(this).setTitle("EMERGENCY: " + title)
                                .setMessage(body).setPositiveButton("OK", null).show();
                    } else {
                        setStatus(title + ": " + body);
                    }
                    request("POST", "/api/notifications/" + id + "/read", null, ignored -> { });
                }
            } catch (Exception e) {
                setStatus("Notification check failed: " + e.getMessage());
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
                sendRealtimeRead(selectedChat.id);
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void connectRealtime() {
        if (token == null || token.isBlank() || (realtimeSocket != null
                && realtimeSocket.getReadyState() != ReadyState.CLOSED) || realtimeConnecting) return;
        realtimeConnecting = true;
        request("POST", "/api/realtime-ticket", null, response -> {
            try {
                String ticket = new JSONObject(response).getString("ticket");
                String websocketBase = server.replaceFirst("^https", "wss").replaceFirst("^http", "ws");
                URI endpoint = URI.create(websocketBase + "/ws/?ticket="
                        + URLEncoder.encode(ticket, StandardCharsets.UTF_8.name()));
                realtimeSocket = new WebSocketClient(endpoint) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    main.post(() -> {
                        realtimeConnecting = false;
                        setStatus("Realtime connected");
                        subscribeRealtimeChats();
                    });
                }

                @Override
                public void onMessage(String message) {
                    main.post(() -> {
                        try {
                            JSONObject event = new JSONObject(message);
                            String type = event.optString("type");
                            if ("message".equals(type) && selectedChat != null
                                    && selectedChat.id.equals(event.optString("chatId"))) {
                                loadMessages();
                            } else if ("delivered".equals(type)) {
                                setStatus("Message delivered");
                            } else if ("presence".equals(type)) {
                                loadChats();
                            } else if ("notification".equals(type)) {
                                loadNotifications();
                            } else if ("typing".equals(type)) {
                                setStatus("Typing: " + event.optString("name"));
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    main.post(() -> {
                        realtimeConnecting = false;
                        setStatus("Realtime unavailable; REST fallback active");
                    });
                }

                @Override
                public void onError(Exception exception) {
                    main.post(() -> {
                        realtimeConnecting = false;
                        setStatus("Realtime unavailable; REST fallback active");
                    });
                }
                };
                realtimeSocket.connect();
            } catch (Exception e) {
                realtimeConnecting = false;
                setStatus("Invalid realtime server URL; REST fallback active");
            }
        });
    }

    private void subscribeRealtimeChats() {
        if (realtimeSocket == null || !realtimeSocket.isOpen()) return;
        for (ChatItem chat : chatItems) {
            realtimeSocket.send("{\"type\":\"subscribe\",\"chatId\":\""
                    + escape(chat.id) + "\"}");
        }
    }

    private void sendRealtimeRead(String chatId) {
        if (!"anonymous".equals(chatId) && realtimeSocket != null && realtimeSocket.isOpen()) {
            realtimeSocket.send("{\"type\":\"read\",\"chatId\":\""
                    + escape(chatId) + "\"}");
        }
    }

    private void chooseDirectChat() {
        request("GET", "/api/users", null, response -> {
            try {
                JSONArray users = new JSONArray(response);
                if (users.length() == 0) {
                    setStatus("No approved employees available");
                    return;
                }
                String[] labels = new String[users.length()];
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.getJSONObject(i);
                    labels[i] = user.optString("displayName") + " · "
                            + user.optString("profession") + " · " + user.optString("workplace")
                            + " (" + user.optString("username") + ")";
                }
                new AlertDialog.Builder(this)
                        .setTitle("Choose an employee")
                        .setItems(labels, (dialog, which) -> {
                            try {
                                String username = users.getJSONObject(which).getString("username");
                                request("POST", "/api/chats/direct",
                                        "{\"username\":\"" + escape(username) + "\"}", responseBody -> {
                                            setStatus("Direct chat opened");
                                            loadChats();
                                        });
                            } catch (Exception e) {
                                setStatus(e.getMessage());
                            }
                        }).show();
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void editProfile() {
        request("GET", "/api/profile", null, response -> {
            try {
                JSONObject profile = new JSONObject(response);
                EditText about = input("About me", false);
                about.setText(profile.optString("about"));
                String[] presenceValues = {"online", "away", "busy", "offline"};
                int selected = 0;
                for (int i = 0; i < presenceValues.length; i++) {
                    if (presenceValues[i].equals(profile.optString("presence"))) selected = i;
                }
                final int defaultPresence = selected;
                new AlertDialog.Builder(this)
                        .setTitle("About me")
                        .setView(about)
                        .setSingleChoiceItems(presenceValues, defaultPresence, null)
                        .setPositiveButton("Save", (dialog, which) -> {
                            int choice = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                            if (choice < 0 || choice >= presenceValues.length) choice = defaultPresence;
                            request("PUT", "/api/profile", "{\"about\":\"" + escape(about.getText().toString())
                                    + "\",\"presence\":\"" + presenceValues[choice] + "\"}",
                                    ignored -> setStatus("Profile saved"));
                        }).setNegativeButton("Cancel", null).show();
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
                main.post(() -> {
                    realtimeConnecting = false;
                    setStatus(e.getMessage());
                });
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

    private void scheduleCall(String mediaType) {
        EditText delay = input("Minutes from now", false);
        delay.setText("10");
        new AlertDialog.Builder(this).setTitle("Schedule " + mediaType + " call")
                .setView(delay)
                .setPositiveButton("Schedule", (dialog, which) -> {
                    try {
                        long minutes = Long.parseLong(delay.getText().toString().trim());
                        if (minutes < 1 || minutes > 10080) throw new NumberFormatException();
                        scheduleCallNow(mediaType, minutes);
                    } catch (NumberFormatException e) {
                        setStatus("Enter a delay from 1 to 10080 minutes");
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void scheduleCallNow(String mediaType, long delayMinutes) {
        if (selectedChat == null) {
            setStatus("Select a direct chat first");
            return;
        }
        request("GET", "/api/chats/" + selectedChat.id + "/peer", null, peerResponse -> {
            try {
                String peer = new JSONObject(peerResponse).getString("username");
                String body = "{\"chatId\":\"" + escape(selectedChat.id) +
                        "\",\"username\":\"" + escape(peer) +
                        "\",\"mediaType\":\"" + escape(mediaType) + "\",\"scheduledAt\":\"" +
                        Instant.now().plus(delayMinutes, ChronoUnit.MINUTES) + "\"}";
                request("POST", "/api/calls/scheduled", body,
                        response -> setStatus(mediaType + " call scheduled for " + delayMinutes + " minutes from now"));
            } catch (Exception e) {
                setStatus("Scheduling is available for direct chats only");
            }
        });
    }

    private void startCall(String mediaType) {
        if (selectedChat == null) {
            setStatus("Select a direct chat first");
            return;
        }
        request("GET", "/api/chats/" + selectedChat.id + "/peer", null, peerResponse -> {
            try {
                String peer = new JSONObject(peerResponse).getString("username");
                String body = "{\"chatId\":\"" + escape(selectedChat.id) + "\",\"username\":\""
                        + escape(peer) + "\",\"mediaType\":\"" + escape(mediaType) + "\"}";
                request("POST", "/api/calls", body, response -> {
                    try {
                        activeCallId = new JSONObject(response).getString("id");
                        setStatus("Call ringing (" + mediaType + "); open WebRTC client for audio/video");
                    } catch (Exception e) {
                        setStatus(e.getMessage());
                    }
                });
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void checkIncomingCalls() {
        request("GET", "/api/calls/incoming", null, response -> {
            try {
                JSONArray calls = new JSONArray(response);
                if (calls.length() == 0) {
                    setStatus("No incoming calls");
                    return;
                }
                JSONObject call = calls.getJSONObject(0);
                incomingCallId = call.getString("id");
                setStatus("Incoming " + call.optString("mediaType") + " call; press Accept");
            } catch (Exception e) {
                setStatus(e.getMessage());
            }
        });
    }

    private void acceptIncomingCall() {
        if (incomingCallId == null) {
            setStatus("Check incoming calls first");
            return;
        }
        request("POST", "/api/calls/" + incomingCallId + "/events",
                "{\"type\":\"accept\",\"payload\":\"\"}", response -> {
                    activeCallId = incomingCallId;
                    incomingCallId = null;
                    setStatus("Call accepted; open WebRTC client for audio/video");
                });
    }

    private void endActiveCall() {
        if (activeCallId == null) {
            setStatus("No active call");
            return;
        }
        request("POST", "/api/calls/" + activeCallId + "/events",
                "{\"type\":\"end\",\"payload\":\"\"}", response -> {
                    activeCallId = null;
                    setStatus("Call ended");
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
        main.removeCallbacksAndMessages(null);
        if (realtimeSocket != null) realtimeSocket.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    private record ChatItem(String id, String name) { }
}
