package sh.zolt.manifest;

import java.util.Objects;
import javax.lang.model.SourceVersion;

/** An exact Java binary class name used by an exclusive test-suite lock. */
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
        if (value.indexOf('.') < 0
                || !SourceVersion.isName(value, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException(
                    "Invalid Java binary class name `" + value + "`: use a fully qualified Java 21 binary name.");
        }
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
