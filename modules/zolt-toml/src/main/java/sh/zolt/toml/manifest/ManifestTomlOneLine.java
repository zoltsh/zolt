package sh.zolt.toml.manifest;

import java.util.StringJoiner;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Renders one parsed TOML value back as the canonical single-line fragment the failure-safe editor
 * requires, so a design §9.9 physical-line diagnostic can show the exact rewrite rather than only
 * naming the offending field.
 *
 * <p>This is diagnostic text, not the canonical writer: it renders what the author already wrote,
 * and returns empty for anything it cannot state exactly on one line.
 */
final class ManifestTomlOneLine {
    private ManifestTomlOneLine() {
    }

    /** {@code "key" = <value>}, or empty when the value cannot be stated exactly on one line. */
    static String assignment(String key, Object value) {
        String rendered = value(value);
        return rendered.isEmpty() ? "" : key(key) + " = " + rendered;
    }

    /** A bare key when TOML allows one, otherwise the quoted form. */
    static String key(String key) {
        if (key == null || key.isEmpty()) {
            return "\"\"";
        }
        for (int index = 0; index < key.length(); index++) {
            if (!isBare(key.charAt(index))) {
                return string(key);
            }
        }
        return key;
    }

    /** The canonical one-line value fragment, or empty when it cannot be rendered. */
    static String value(Object value) {
        if (value instanceof String text) {
            return string(text);
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        if (value instanceof Long || value instanceof Double) {
            return value.toString();
        }
        if (value instanceof TomlArray array) {
            return array(array);
        }
        if (value instanceof TomlTable table) {
            return table(table);
        }
        return "";
    }

    private static String array(TomlArray array) {
        StringJoiner joined = new StringJoiner(", ", "[", "]");
        for (int index = 0; index < array.size(); index++) {
            String element = value(array.get(index));
            if (element.isEmpty()) {
                return "";
            }
            joined.add(element);
        }
        return joined.toString();
    }

    private static String table(TomlTable table) {
        if (table.isEmpty()) {
            return "{}";
        }
        StringJoiner joined = new StringJoiner(", ", "{ ", " }");
        for (var entry : table.entrySet()) {
            String member = value(entry.getValue());
            if (member.isEmpty()) {
                return "";
            }
            joined.add(key(entry.getKey()) + " = " + member);
        }
        return joined.toString();
    }

    private static String string(String text) {
        StringBuilder encoded = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\t' -> encoded.append("\\t");
                case '\n' -> encoded.append("\\n");
                case '\f' -> encoded.append("\\f");
                case '\r' -> encoded.append("\\r");
                default -> encoded.append(character);
            }
        }
        return encoded.append('"').toString();
    }

    private static boolean isBare(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_'
                || character == '-';
    }
}
