package sh.zolt.toml.schema;

import java.util.Objects;
import java.util.regex.Pattern;

/** One closed, canonically ordered member of a manifest inline object. */
public record ManifestObjectMember(
        String name,
        ManifestValueKind valueKind,
        boolean required,
        int canonicalOrder) {
    private static final Pattern LOWER_CAMEL = Pattern.compile("[a-z][A-Za-z0-9]*");

    public ManifestObjectMember {
        Objects.requireNonNull(name, "Manifest object member name is required.");
        if (!LOWER_CAMEL.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Manifest object member names must use ASCII lower camel case.");
        }
        Objects.requireNonNull(valueKind, "Manifest object member value kind is required.");
        if (acceptsObject(valueKind)) {
            throw new IllegalArgumentException(
                    "Nested manifest object members are not supported by the closed object schema.");
        }
        if (canonicalOrder < 0) {
            throw new IllegalArgumentException(
                    "Manifest object member canonical order must not be negative.");
        }
    }

    private static boolean acceptsObject(ManifestValueKind valueKind) {
        return switch (valueKind) {
            case INLINE_TABLE,
                    INLINE_TABLE_ARRAY,
                    STRING_OR_INLINE_TABLE,
                    BOOLEAN_OR_STRING_OR_INLINE_TABLE -> true;
            default -> false;
        };
    }
}
