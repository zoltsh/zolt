package sh.zolt.manifest;

import java.util.Objects;
import javax.lang.model.SourceVersion;

/** An exact, package-qualified portable ASCII Java binary class name. */
public record JavaBinaryClassName(String value) implements Comparable<JavaBinaryClassName> {
    public JavaBinaryClassName {
        Objects.requireNonNull(value, "Java binary class name must not be null.");
        ManifestModelValues.requireNonBlank(value, "Java binary class name");
        ManifestModelValues.rejectControlCharacters(value, "Java binary class name");
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid Java binary class name `" + value + "`: suite locks require an exact class.");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid Java binary class name `" + value + "`: use binary-name dots, not path separators.");
        }
        if (!hasPortableQualifiedShape(value)
                || !SourceVersion.isName(value, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException(
                    "Invalid Java binary class name `" + value
                            + "`: use a fully qualified portable ASCII Java 21 binary name.");
        }
    }

    private static boolean hasPortableQualifiedShape(String value) {
        boolean qualified = false;
        boolean segmentStart = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '.') {
                if (segmentStart) {
                    return false;
                }
                qualified = true;
                segmentStart = true;
            } else if (segmentStart) {
                if (!isAsciiIdentifierStart(character)) {
                    return false;
                }
                segmentStart = false;
            } else if (!isAsciiIdentifierStart(character)
                    && (character < '0' || character > '9')) {
                return false;
            }
        }
        return qualified && !segmentStart;
    }

    private static boolean isAsciiIdentifierStart(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character == '_'
                || character == '$';
    }

    @Override
    public int compareTo(JavaBinaryClassName other) {
        Objects.requireNonNull(other, "Compared Java binary class name must not be null.");
        return ManifestModelValues.CODE_POINT_ORDER.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
