package sh.zolt.toml.schema;

import java.util.Set;

/** Typed shape-validation route for direct path and environment-name fields. */
public enum ManifestValidationCategory {
    NONE(Set.of()),
    MANIFEST_RELATIVE_PATH(Set.of(ManifestValueKind.STRING, ManifestValueKind.STRING_ARRAY)),
    WORKSPACE_MEMBER_PATH(Set.of(
            ManifestValueKind.STRING,
            ManifestValueKind.STRING_ARRAY,
            ManifestValueKind.BOOLEAN_OR_STRING_ARRAY)),
    WORKSPACE_MEMBER_PATTERN(Set.of(ManifestValueKind.STRING, ManifestValueKind.STRING_ARRAY)),
    RESOURCE_GLOB(Set.of(ManifestValueKind.STRING, ManifestValueKind.STRING_ARRAY)),
    ENVIRONMENT_NAME(Set.of(ManifestValueKind.STRING, ManifestValueKind.STRING_ARRAY)),
    ENVIRONMENT_MAP_KEYS(Set.of(ManifestValueKind.INLINE_TABLE)),
    ENVIRONMENT_MAP_KEYS_AND_VALUES(Set.of(ManifestValueKind.INLINE_TABLE));

    private final Set<ManifestValueKind> acceptedKinds;

    ManifestValidationCategory(Set<ManifestValueKind> acceptedKinds) {
        this.acceptedKinds = Set.copyOf(acceptedKinds);
    }

    boolean accepts(ManifestValueKind kind) {
        return this == NONE || acceptedKinds.contains(kind);
    }
}
