package kz.zacklayr.tlschat;

import java.text.Normalizer;
import java.util.Locale;

/** Deterministic first-line helpdesk triage; human support remains authoritative. */
public final class HelpdeskBot {
    private HelpdeskBot() {
    }

    public record Reply(String text, String category, boolean urgent) {
    }

    public static Reply answer(String displayName, String question) {
        String name = displayName == null || displayName.isBlank() ? "коллега" : displayName.trim();
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return new Reply("🤖 Опишите проблему несколькими словами — бот определит категорию и передаст обращение специалисту.",
                    "general", false);
        }

        if (containsAny(normalized, "взлом", "утеч", "фишинг", "вредонос", "компромет", "уязвим", "несанкционирован")) {
            return new Reply("🚨 Зафиксировал возможный инцидент безопасности. Не передавайте пароли и токены, "
                    + "сохраните время и описание события. Обращение помечено как срочное и немедленно передано специалисту кибербезопасности.",
                    "security", true);
        }
        if (containsAny(normalized, "парол", "логин", "войти", "вход", "доступ", "аккаунт", "регистрац")) {
            return new Reply("🔐 Категория «доступ»: проверьте логин, срок временного пароля и статус заявки. "
                    + "Не отправляйте пароль в чат. Если вход не восстановится, оператор проверит учётную запись.",
                    "access", false);
        }
        if (containsAny(normalized, "звон", "видео", "микрофон", "камера", "webrtc", "звук")) {
            return new Reply("📞 Категория «звонки»: проверьте разрешения камеры/микрофона, HTTPS и стабильность сети. "
                    + "Для проблем соединения оператор проверит TURN-настройки и журнал звонка.",
                    "calls", false);
        }
        if (containsAny(normalized, "файл", "фото", "видео", "вложен", "загруз", "скач")) {
            return new Reply("📎 Категория «файлы»: проверьте размер вложения (до 25 МБ) и доступ к чату. "
                    + "Если загрузка повторяется, укажите имя файла и время ошибки.",
                    "files", false);
        }
        if (containsAny(normalized, "ошиб", "не работ", "слом", "баг", "краш", "исключен", "ошибка")) {
            return new Reply("🛠️ Категория «ошибка»: пришлите шаги воспроизведения, название чата и время события. "
                    + "Не отправляйте секреты или токены — оператор продолжит диагностику.",
                    "incident", false);
        }
        if (containsAny(normalized, "чат", "сообщен", "уведомлен", "не приходит", "не вижу")) {
            return new Reply("💬 Категория «чаты»: проверьте, что вы одобрены и состоите в нужной группе. "
                    + "Оператор проверит членство, права роли и доставку сообщений.",
                    "messaging", false);
        }
        if (containsAny(normalized, "привет", "здравств", "добрый", "мяу")) {
            return new Reply("🤖 Здравствуйте, " + name + "! Опишите проблему — я классифицирую её и передам оператору.",
                    "general", false);
        }
        return new Reply("🤖 Обращение принято, " + name + ". Я не нашёл готовую категорию: добавьте шаги воспроизведения, "
                + "время и ожидаемый результат. Дежурный специалист продолжит обработку.", "general", false);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }
}
