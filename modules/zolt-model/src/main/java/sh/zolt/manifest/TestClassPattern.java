package sh.zolt.manifest;

import java.util.Objects;

/** A test-suite pattern over fully qualified Java binary class names. */
public record TestClassPattern(String value) {
    public TestClassPattern {
        Objects.requireNonNull(value, "Test class pattern must not be null.");
        ManifestModelValues.requireNonBlank(value, "Test class pattern");
        ManifestModelValues.rejectControlCharacters(value, "Test class pattern");
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid test class pattern `" + value
                            + "`: match Java binary class names, not filesystem paths.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
