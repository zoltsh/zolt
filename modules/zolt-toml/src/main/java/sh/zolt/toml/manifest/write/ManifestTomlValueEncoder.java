package sh.zolt.toml.manifest.write;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import sh.zolt.toml.schema.FormattingPolicy;
import sh.zolt.toml.schema.ManifestField;

/** Canonical TOML value fragments for authored-manifest writers. */
final class ManifestTomlValueEncoder {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ManifestTomlValueEncoder() {
    }

    static String basicString(String value) {
        Objects.requireNonNull(value, "TOML string value is required.");
        StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
        for (int offset = 0; offset < value.length();) {
            char character = value.charAt(offset);
            if (Character.isSurrogate(character)) {
                if (!Character.isHighSurrogate(character)
                        || offset + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                    throw new IllegalArgumentException(
                            "TOML strings cannot contain an unpaired UTF-16 surrogate.");
                }
                encoded.append(character).append(value.charAt(offset + 1));
                offset += 2;
                continue;
            }
            appendBasicStringCharacter(encoded, character);
            offset++;
        }
        return encoded.append('"').toString();
    }

    static String quotedKey(String key) {
        return basicString(Objects.requireNonNull(key, "TOML dynamic key is required."));
    }

    static String booleanValue(boolean value) {
        return Boolean.toString(value);
    }

    static String integer(int value) {
        return Integer.toString(value);
    }

    static String decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Authored TOML decimal value must be finite.");
        }
        if (value == 0.0) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String array(List<String> encodedValues) {
        Objects.requireNonNull(encodedValues, "Encoded TOML array values are required.");
        StringJoiner joined = new StringJoiner(", ", "[", "]");
        for (String value : encodedValues) {
            joined.add(requireOneLine(value, "Encoded TOML array value"));
        }
        return joined.toString();
    }

    static String fieldArray(ManifestField field, List<String> encodedValues) {
        ManifestField descriptor = Objects.requireNonNull(
                field, "Manifest array field is required.");
        String key = descriptor.path().segments().getLast();
        if (key.startsWith("<") && key.endsWith(">")) {
            throw new IllegalArgumentException(
                    "Dynamic manifest array fields require a concrete key.");
        }
        return fieldArray(descriptor, key, encodedValues);
    }

    static String fieldArray(
            ManifestField field, String concreteKey, List<String> encodedValues) {
        ManifestField descriptor = Objects.requireNonNull(
                field, "Manifest array field is required.");
        String actualKey = Objects.requireNonNull(
                concreteKey, "Manifest array field key is required.");
        String pattern = descriptor.path().segments().getLast();
        if (!(pattern.startsWith("<") && pattern.endsWith(">"))
                && !pattern.equals(actualKey)) {
            throw new IllegalArgumentException(
                    "Concrete manifest array field key does not match `" + pattern + "`.");
        }
        String key = renderKey(actualKey);
        String inline = array(encodedValues);
        int assignmentWidth = key.codePointCount(0, key.length())
                + 3
                + inline.codePointCount(0, inline.length());
        if (encodedValues.isEmpty()
                || descriptor.formatting() == FormattingPolicy.ONE_LINE
                || assignmentWidth <= 100) {
            return inline;
        }
        StringJoiner wrapped = new StringJoiner(",\n    ", "[\n    ", ",\n]");
        for (String value : encodedValues) {
            wrapped.add(requireOneLine(value, "Encoded TOML array value"));
        }
        return wrapped.toString();
    }

    static InlineMember member(String key, String encodedValue) {
        requireBareKey(key);
        return new InlineMember(key, encodedValue);
    }

    static InlineMember quotedMember(String key, String encodedValue) {
        return new InlineMember(quotedKey(key), encodedValue);
    }

    static String inlineObject(List<InlineMember> members) {
        Objects.requireNonNull(members, "TOML inline-object members are required.");
        if (members.isEmpty()) {
            throw new IllegalArgumentException(
                    "Canonical authored TOML cannot contain an empty inline object.");
        }
        StringJoiner joined = new StringJoiner(", ", "{ ", " }");
        for (InlineMember member : members) {
            InlineMember present = Objects.requireNonNull(
                    member, "TOML inline-object member is required.");
            joined.add(present.encodedKey() + " = " + present.encodedValue());
        }
        return joined.toString();
    }

    record InlineMember(String encodedKey, String encodedValue) {
        InlineMember {
            encodedKey = requireOneLine(encodedKey, "Encoded TOML inline-object key");
            encodedValue = requireOneLine(encodedValue, "Encoded TOML inline-object value");
        }
    }

    private static void appendBasicStringCharacter(StringBuilder encoded, char character) {
        switch (character) {
            case '"' -> encoded.append("\\\"");
            case '\\' -> encoded.append("\\\\");
            case '\b' -> encoded.append("\\b");
            case '\t' -> encoded.append("\\t");
            case '\n' -> encoded.append("\\n");
            case '\f' -> encoded.append("\\f");
            case '\r' -> encoded.append("\\r");
            default -> {
                if (character <= 0x1F || character == 0x7F) {
                    appendUnicodeEscape(encoded, character);
                } else {
                    encoded.append(character);
                }
            }
        }
    }

    private static void appendUnicodeEscape(StringBuilder encoded, char character) {
        encoded.append("\\u")
                .append(HEX[(character >>> 12) & 0xF])
                .append(HEX[(character >>> 8) & 0xF])
                .append(HEX[(character >>> 4) & 0xF])
                .append(HEX[character & 0xF]);
    }

    private static void requireBareKey(String key) {
        Objects.requireNonNull(key, "TOML inline-object member key is required.");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("TOML inline-object member key must not be empty.");
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            boolean bare = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!bare) {
                throw new IllegalArgumentException(
                        "TOML inline-object member key requires quotedMember: `" + key + "`.");
            }
        }
    }

    private static String renderKey(String key) {
        if (key.isEmpty()) {
            return quotedKey(key);
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            boolean bare = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!bare) {
                return quotedKey(key);
            }
        }
        return key;
    }

    private static String requireOneLine(String value, String label) {
        Objects.requireNonNull(value, label + " is required.");
        if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be a nonempty one-line fragment.");
        }
        return value;
    }
}
