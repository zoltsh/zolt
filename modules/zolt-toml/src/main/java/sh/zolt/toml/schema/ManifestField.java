package sh.zolt.toml.schema;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Schema metadata for one accepted manifest field path. */
public record ManifestField(
        ManifestPath path,
        ManifestValueKind valueKind,
        FormattingPolicy formatting,
        MutationPolicy mutation,
        int canonicalOrder,
        Optional<String> symbolFamily,
        ManifestValidationCategory validation,
        Map<String, ManifestDynamicKeyGrammar> dynamicKeyGrammars) {
    public ManifestField {
        Objects.requireNonNull(path, "Manifest field path is required.");
        Objects.requireNonNull(valueKind, "Manifest field value kind is required.");
        Objects.requireNonNull(formatting, "Manifest field formatting policy is required.");
        Objects.requireNonNull(mutation, "Manifest field mutation policy is required.");
        symbolFamily = Objects.requireNonNull(
                symbolFamily, "Manifest field symbol family must not be null.");
        symbolFamily = symbolFamily.map(ManifestField::requireSymbolFamilyName);
        if (symbolFamily.isPresent()
                && valueKind != ManifestValueKind.STRING
                && valueKind != ManifestValueKind.STRING_ARRAY) {
            throw new IllegalArgumentException(
                    "Manifest symbol families require a string or string-array field.");
        }
        validation = Objects.requireNonNull(
                validation, "Manifest field validation category is required.");
        if (!validation.accepts(valueKind)) {
            throw new IllegalArgumentException(
                    "Manifest field validation category `" + validation
                            + "` does not accept value kind `" + valueKind + "`.");
        }
        dynamicKeyGrammars = ManifestDynamicKeyGrammar.copyFor(path, dynamicKeyGrammars);
        if (canonicalOrder < 0) {
            throw new IllegalArgumentException("Manifest field canonical order must not be negative.");
        }
    }

    private static String requireSymbolFamilyName(String name) {
        if (name.isBlank() || !name.equals(name.strip())) {
            throw new IllegalArgumentException("Manifest field symbol family name must not be blank.");
        }
        return name;
    }
}
