package sh.zolt.toml.schema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One closed family of canonical lowercase kebab-case manifest symbols. */
public record ManifestSymbolFamily(String name, List<String> values) {
    private static final Pattern SYMBOL = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    public ManifestSymbolFamily {
        name = requireSymbol(name, "Manifest symbol family name");
        Objects.requireNonNull(values, "Manifest symbol family values are required.");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Manifest symbol family values must not be empty.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String canonical = requireSymbol(value, "Manifest symbol value");
            if (!unique.add(canonical)) {
                throw new IllegalArgumentException(
                        "Duplicate manifest symbol `" + canonical + "` in family `" + name + "`.");
            }
        }
        values = List.copyOf(unique);
    }

    public boolean accepts(String value) {
        return values.contains(value);
    }

    private static String requireSymbol(String value, String label) {
        if (value == null || !SYMBOL.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must use lowercase kebab-case.");
        }
        return value;
    }
}
