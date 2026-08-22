package sh.zolt.toml.schema;

import java.util.Map;
import java.util.Objects;

/** A schema descriptor matched to one concrete manifest path and its dynamic bindings. */
public record ManifestSchemaMatch<T>(T descriptor, Map<String, String> bindings) {
    public ManifestSchemaMatch {
        Objects.requireNonNull(descriptor, "Matched manifest schema descriptor is required.");
        Objects.requireNonNull(bindings, "Matched manifest schema bindings are required.");
        bindings = Map.copyOf(bindings);
    }
}
