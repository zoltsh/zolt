package sh.zolt.maven;

import java.util.Objects;

/** Shared validation for values that become Maven repository URI or filesystem path material. */
public final class MavenRepositoryValue {
    private static final String RESERVED = "/\\?#:%";

    private MavenRepositoryValue() {
    }

    public static String groupId(String value) {
        String validated = segment(value, "group");
        String[] parts = validated.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw unsafe("group", value);
            }
        }
        return validated;
    }

    public static String artifactId(String value) {
        return segment(value, "artifact");
    }

    public static String version(String value) {
        return segment(value, "version");
    }

    public static String classifier(String value) {
        return segment(value, "classifier");
    }

    public static String extension(String value) {
        return segment(value, "extension");
    }

    private static String segment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CoordinateParseException("Maven repository " + name + " is required.");
        }
        if (value.equals(".") || value.equals("..")) {
            throw unsafe(name, value);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw unsafe(name, value);
                }
                int codePoint = Character.toCodePoint(character, value.charAt(++index));
                if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                    throw unsafe(name, value);
                }
            } else if (Character.isLowSurrogate(character)
                    || Character.isWhitespace(character)
                    || Character.isISOControl(character)
                    || RESERVED.indexOf(character) >= 0) {
                throw unsafe(name, value);
            }
        }
        return value;
    }

    private static CoordinateParseException unsafe(String name, String value) {
        return new CoordinateParseException(
                "Maven repository " + name + " `" + Objects.toString(value) + "` is not repository-safe.");
    }
}
