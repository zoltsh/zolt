package sh.zolt.toml.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Domain grammar used to validate one dynamic manifest path segment. */
public enum ManifestDynamicKeyGrammar {
    LOCAL_ID,
    MAVEN_COORDINATE,
    EXTERNAL_JAR_ATTRIBUTE;

    static Map<String, ManifestDynamicKeyGrammar> copyFor(
            ManifestPath path,
            Map<String, ManifestDynamicKeyGrammar> grammars) {
        Objects.requireNonNull(path, "Manifest descriptor path is required.");
        Objects.requireNonNull(grammars, "Manifest dynamic-key grammars are required.");
        LinkedHashMap<String, ManifestDynamicKeyGrammar> ordered = new LinkedHashMap<>();
        for (String placeholder : path.placeholderNames()) {
            ManifestDynamicKeyGrammar grammar = grammars.get(placeholder);
            if (grammar == null) {
                throw new IllegalArgumentException(
                        "Manifest path `" + path + "` requires a grammar for placeholder `<"
                                + placeholder + ">`.");
            }
            if (ordered.putIfAbsent(placeholder, grammar) != null) {
                throw new IllegalArgumentException(
                        "Manifest path `" + path + "` repeats placeholder `<" + placeholder + ">`.");
            }
        }
        if (ordered.size() != grammars.size()) {
            throw new IllegalArgumentException(
                    "Manifest dynamic-key grammars must name only placeholders in `" + path + "`.");
        }
        return Collections.unmodifiableMap(ordered);
    }
}
