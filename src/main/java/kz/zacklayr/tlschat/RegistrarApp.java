package kz.zacklayr.tlschat;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.GridLayout;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RegistrarApp {

    // ─── Отделы Avengers Games (из плана проекта) ─────────────────────────────
    private static final String[] DEPARTMENTS = {
        "Высшее руководство",
        "Разработка движка",
        "Разработка интеграций и портирования",
        "Геймдизайн",
        "Арт и анимация",
        "Нарратив и сценарий",
        "Звук",
        "Локализация и адаптация",
        "Техподдержка",
        "Кибербезопасность",
        "Оборудование и закупки",
        "HR",
        "QA",
        "Управление проектами"
    };
    // ──────────────────────────────────────────────────────────────────────────

    private final HttpClient http = HttpClient.newHttpClient();
    private final String defaultServerUrl;
    private String serverUrl;
    private String token;
    private List<RegistrationRequest> requests = List.of();
    private RequestTableModel tableModel;
    private JTable table;
    private JTextField fullName;
    private JTextField profession;
    private JComboBox<String> workplace;
    private JTextField region;
    private JTextField phone;
    private JTextField accountUsername;
    private JPasswordField accountPassword;
    private JCheckBox globalWrite;
    private JCheckBox regionalWrite;
    private JCheckBox departmentLead;
    private JCheckBox supportAgent;
    private JCheckBox hiddenData;
    private JCheckBox manageUsers;
    private JCheckBox manageChats;

    private RegistrarApp(String defaultServerUrl) {
        this.defaultServerUrl = defaultServerUrl;
    }

    public static void main(String[] args) {
        String serverUrl = args.length == 0 ? "http://localhost:8080" : args[0];
        SwingUtilities.invokeLater(() -> new RegistrarApp(serverUrl).showLogin());
    }

    private void showLogin() {
        JFrame frame = new JFrame("TLS Chat — вход регистратора");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTextField server = new JTextField(defaultServerUrl);
        JTextField username = new JTextField();
        JPasswordField password = new JPasswordField();
        JButton login = new JButton("Войти");
        form.add(new JLabel("Адрес сервера:"));
        form.add(server);
        form.add(new JLabel("Логин регистратора:"));
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
        JFrame frame = new JFrame("TLS Chat — панель регистратора");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        tableModel = new RequestTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> loadSelectedRequest());

        JPanel details = new JPanel(new GridLayout(0, 1, 5, 5));
        details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        profession = new JTextField();
        workplace = new JComboBox<>(DEPARTMENTS);
        region = new JTextField();
        phone = new JTextField();
        accountUsername = new JTextField();
        accountPassword = new JPasswordField();
        fullName = new JTextField();
        fullName.setEditable(false);
        details.add(new JLabel("ФИО:"));
        details.add(fullName);
        details.add(new JLabel("Профессия:"));
        details.add(profession);
        details.add(new JLabel("Отдел:"));
        details.add(workplace);
        details.add(new JLabel("Регион / город:"));
        details.add(region);
        details.add(new JLabel("Телефон:"));
        details.add(phone);
        details.add(new JLabel("Новый логин:"));
        details.add(accountUsername);
        details.add(new JLabel("Временный пароль:"));
        details.add(accountPassword);
        globalWrite = permission("Писать в глобальный чат", details);
        regionalWrite = permission("Писать в региональные чаты", details);
        departmentLead = permission("Руководитель отдела", details);
        supportAgent = permission("Оператор техподдержки", details);
        hiddenData = permission("Просматривать скрытые данные", details);
        manageUsers = permission("Управлять пользователями", details);
        manageChats = permission("Управлять чатами", details);
        JButton approve = new JButton("Одобрить");
        JButton reject = new JButton("Отклонить");
        JButton refresh = new JButton("Обновить заявки");
        approve.addActionListener(event -> decideSelected("APPROVED"));
        reject.addActionListener(event -> decideSelected("REJECTED"));
        refresh.addActionListener(event -> refreshRequests());
        JPanel actions = new JPanel();
        actions.add(approve);
        actions.add(reject);
        actions.add(refresh);
        details.add(actions);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(table), details);
        split.setResizeWeight(0.55);
        frame.add(split);
        frame.setSize(760, 420);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        refreshRequests();
    }

    private JCheckBox permission(String label, JPanel parent) {
        JCheckBox box = new JCheckBox(label);
        parent.add(box);
        return box;
    }

    private void refreshRequests() {
        try {
            requests = parseRequests(request("GET", "/api/registrar/requests", null));
            tableModel.setRequests(requests);
            if (!requests.isEmpty()) {
                table.setRowSelectionInterval(0, 0);
            }
        } catch (Exception e) {
            showError(e);
        }
    }

    private void loadSelectedRequest() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= requests.size()) {
            return;
        }
        RegistrationRequest request = requests.get(table.convertRowIndexToModel(row));
        fullName.setText(request.fullName());
        profession.setText(request.profession());
        // Пытаемся найти отдел из заявки в списке; если нет — оставляем первый
        String reqWorkplace = request.workplace();
        boolean found = false;
        for (int i = 0; i < workplace.getItemCount(); i++) {
            if (workplace.getItemAt(i).equals(reqWorkplace)) {
                workplace.setSelectedIndex(i);
                found = true;
                break;
            }
        }
        if (!found) workplace.setSelectedIndex(0);
        region.setText(request.region());
        phone.setText(request.phone());
        accountUsername.setText("");
        accountPassword.setText("");
        setPermissions(request.permissions());
    }

    private void decideSelected(String status) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= requests.size()) {
            return;
        }
        RegistrationRequest request = requests.get(table.convertRowIndexToModel(row));
        String body = "{\"status\":\"" + status + "\",\"fullName\":\"" + escape(request.fullName()) +
                "\",\"profession\":\"" + escape(profession.getText()) + "\",\"workplace\":\"" +
                escape((String) workplace.getSelectedItem()) + "\",\"region\":\"" + escape(region.getText()) +
                "\",\"phone\":\"" + escape(phone.getText()) + "\",\"username\":\"" +
                escape(accountUsername.getText()) + "\",\"password\":\"" +
                escape(new String(accountPassword.getPassword())) + "\",\"permissions\":\"" +
                escape(selectedPermissions()) + "\"}";
        try {
            request("POST", "/api/registrar/requests/" +
                    URLEncoder.encode(request.id(), StandardCharsets.UTF_8), body);
            refreshRequests();
        } catch (Exception e) {
            showError(e);
        }
    }

    private String login(String url, String username, String password) throws Exception {
        String response = request(url, "POST", "/api/registrar/login",
                "{\"username\":\"" + escape(username) + "\",\"password\":\"" + escape(password) + "\"}", null);
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
        HttpRequest request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
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

    private String selectedPermissions() {
        List<String> selected = new ArrayList<>();
        if (globalWrite.isSelected()) selected.add("global_write");
        if (regionalWrite.isSelected()) selected.add("regional_write");
        if (departmentLead.isSelected()) selected.add("department_lead");
        if (supportAgent.isSelected()) selected.add("support_agent");
        if (hiddenData.isSelected()) selected.add("hidden_data");
        if (manageUsers.isSelected()) selected.add("manage_users");
        if (manageChats.isSelected()) selected.add("manage_chats");
        return String.join(",", selected);
    }

    private void setPermissions(String permissions) {
        List<String> selected = List.of(permissions.split(","));
        globalWrite.setSelected(selected.contains("global_write"));
        regionalWrite.setSelected(selected.contains("regional_write"));
        departmentLead.setSelected(selected.contains("department_lead"));
        supportAgent.setSelected(selected.contains("support_agent"));
        hiddenData.setSelected(selected.contains("hidden_data"));
        manageUsers.setSelected(selected.contains("manage_users"));
        manageChats.setSelected(selected.contains("manage_chats"));
    }

    private static List<RegistrationRequest> parseRequests(String json) {
        List<RegistrationRequest> result = new ArrayList<>();
        int start = 0;
        while ((start = json.indexOf('{', start)) >= 0) {
            int end = json.indexOf('}', start);
            if (end < 0) break;
            String object = json.substring(start, end + 1);
            result.add(new RegistrationRequest(jsonValue(object, "id"),
                    jsonValue(object, "fullName"), jsonValue(object, "profession"),
                    jsonValue(object, "workplace"), jsonValue(object, "region"),
                    jsonValue(object, "phone"), jsonValue(object, "permissions")));
            start = end + 1;
        }
        return result;
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Ответ сервера не содержит поле " + key);
        }
        start += marker.length();
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                value.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return value.toString();
            } else {
                value.append(current);
            }
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

    private record RegistrationRequest(String id, String fullName, String profession,
                                       String workplace, String region, String phone,
                                       String permissions) {
    }

    private static final class RequestTableModel extends AbstractTableModel {
        private List<RegistrationRequest> requests = List.of();

        void setRequests(List<RegistrationRequest> requests) {
            this.requests = requests;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return requests.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return new String[]{"ФИО", "Профессия", "Отдел", "Регион"}[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            RegistrationRequest request = requests.get(row);
            return new String[]{request.fullName(), request.profession(), request.workplace(),
                    request.region()}[column];
        }
    }
}
