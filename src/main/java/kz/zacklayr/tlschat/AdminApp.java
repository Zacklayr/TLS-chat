package kz.zacklayr.tlschat;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.GridLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public final class AdminApp {
    private final HttpClient http = HttpClient.newHttpClient();
    private final String defaultServerUrl;
    private String serverUrl;
    private String token;
    private ChatTableModel tableModel;
    private JTextField id;
    private JTextField type;
    private JTextField name;
    private JTextField color;
    private JTextField professionRule;
    private JTextField workplaceRule;
    private JTextField regionRule;
    private JTextField writePermissions;

    private AdminApp(String defaultServerUrl) {
        this.defaultServerUrl = defaultServerUrl;
    }

    public static void main(String[] args) {
        String serverUrl = args.length == 0 ? "http://localhost:8080" : args[0];
        SwingUtilities.invokeLater(() -> new AdminApp(serverUrl).showLogin());
    }

    private void showLogin() {
        JFrame frame = new JFrame("TLS Chat - вход администратора");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTextField server = new JTextField(defaultServerUrl);
        JTextField username = new JTextField();
        JPasswordField password = new JPasswordField();
        JButton login = new JButton("Войти");
        form.add(new JLabel("Адрес сервера:"));
        form.add(server);
        form.add(new JLabel("Логин администратора:"));
        form.add(username);
        form.add(new JLabel("Пароль:"));
        form.add(password);
        form.add(new JLabel());
        form.add(login);
        login.addActionListener(event -> {
            try {
                serverUrl = trimServerUrl(server.getText());
                token = login(serverUrl, username.getText(), new String(password.getPassword()));
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
        JFrame frame = new JFrame("TLS Chat - панель администратора");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        tableModel = new ChatTableModel();
        JTable table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel form = new JPanel(new GridLayout(0, 2, 5, 5));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        id = field(form, "ID чата:");
        type = field(form, "Тип:");
        name = field(form, "Название:");
        color = field(form, "Цвет:");
        professionRule = field(form, "Профессия (пусто - все):");
        workplaceRule = field(form, "Отдел (пусто - все):");
        regionRule = field(form, "Регион (пусто - все):");
        writePermissions = field(form, "Права записи (через запятую):");
        JButton create = new JButton("Создать чат");
        JButton refresh = new JButton("Обновить");
        form.add(create);
        form.add(refresh);
        create.addActionListener(event -> createChat());
        refresh.addActionListener(event -> refreshChats());

        frame.add(new JScrollPane(table), java.awt.BorderLayout.CENTER);
        frame.add(form, java.awt.BorderLayout.SOUTH);
        frame.setSize(760, 420);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        refreshChats();
    }

    private JTextField field(JPanel parent, String label) {
        parent.add(new JLabel(label));
        JTextField result = new JTextField();
        parent.add(result);
        return result;
    }

    private void refreshChats() {
        try {
            tableModel.setChats(parseChats(request("GET", "/api/admin/chats", null)));
        } catch (Exception e) {
            showError(e);
        }
    }

    private void createChat() {
        String body = "{\"id\":\"" + escape(id.getText()) + "\",\"type\":\"" + escape(type.getText()) +
                "\",\"name\":\"" + escape(name.getText()) + "\",\"color\":\"" + escape(color.getText()) +
                "\",\"professionRule\":\"" + escape(professionRule.getText()) +
                "\",\"workplaceRule\":\"" + escape(workplaceRule.getText()) +
                "\",\"regionRule\":\"" + escape(regionRule.getText()) +
                "\",\"writePermissions\":\"" + escape(writePermissions.getText()) + "\"}";
        try {
            request("POST", "/api/admin/chats", body);
            refreshChats();
        } catch (Exception e) {
            showError(e);
        }
    }

    private String login(String url, String username, String password) throws Exception {
        String response = request(url, "POST", "/api/admin/login",
                "{\"username\":\"" + escape(username.trim()) + "\",\"password\":\"" + escape(password) + "\"}", null);
        return jsonValue(response, "token");
    }

    private String request(String method, String path, String body) throws Exception {
        return request(serverUrl, method, path, body, token);
    }

    private String request(String url, String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url + path))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            builder.header("Authorization", "Bearer " + authorization);
        }
        HttpResponse<String> response = http.send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(errorMessage(response));
        }
        return response.body();
    }

    private static String trimServerUrl(String value) {
        String result = value.trim();
        if (!result.contains("://")) {
            result = "https://" + result;
        }
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            throw new IllegalArgumentException("Адрес должен начинаться с http:// или https://");
        }
        return result;
    }

    private static String errorMessage(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().trim();
        if (body.startsWith("{")) {
            try {
                return "HTTP " + response.statusCode() + ": " + jsonValue(body, "error");
            } catch (IllegalStateException ignored) {
                // Fall through for non-API responses such as a proxy error page.
            }
        }
        if (body.contains("No such app")) {
            return "Heroku-приложение не найдено. Проверьте актуальный адрес сервера.";
        }
        return "Сервер вернул HTTP " + response.statusCode() + ". Проверьте адрес сервера.";
    }

    private static List<ChatDefinition> parseChats(String json) {
        List<ChatDefinition> result = new ArrayList<>();
        int start = 0;
        while ((start = json.indexOf('{', start)) >= 0) {
            int end = json.indexOf('}', start);
            if (end < 0) break;
            String object = json.substring(start, end + 1);
            String regRule = "";
            try {
                regRule = jsonValue(object, "regionRule");
            } catch (Exception ignored) {
            }
            result.add(new ChatDefinition(jsonValue(object, "id"), jsonValue(object, "type"),
                    jsonValue(object, "name"), jsonValue(object, "professionRule"),
                    jsonValue(object, "workplaceRule"), regRule, jsonValue(object, "writePermissions")));
            start = end + 1;
        }
        return result;
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalStateException("Ответ сервера не содержит поле " + key);
        start += marker.length();
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                value.append(current);
                escaped = false;
            } else if (current == '\\') escaped = true;
            else if (current == '"') return value.toString();
            else value.append(current);
        }
        throw new IllegalStateException("Некорректный JSON");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void showError(Exception exception) {
        JOptionPane.showMessageDialog(null, exception.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private record ChatDefinition(String id, String type, String name, String professionRule,
                                  String workplaceRule, String regionRule, String writePermissions) {
    }

    private static final class ChatTableModel extends AbstractTableModel {
        private List<ChatDefinition> chats = List.of();

        void setChats(List<ChatDefinition> chats) {
            this.chats = chats;
            fireTableDataChanged();
        }

        public int getRowCount() {
            return chats.size();
        }

        public int getColumnCount() {
            return 7;
        }

        public String getColumnName(int column) {
            return new String[]{"ID", "Тип", "Название", "Профессия", "Отдел", "Регион", "Права записи"}[column];
        }

        public Object getValueAt(int row, int column) {
            ChatDefinition chat = chats.get(row);
            return new String[]{chat.id(), chat.type(), chat.name(), chat.professionRule(),
                    chat.workplaceRule(), chat.regionRule(), chat.writePermissions()}[column];
        }
    }
}
