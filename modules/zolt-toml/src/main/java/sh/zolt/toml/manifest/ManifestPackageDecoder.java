package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.toml.schema.FinalManifestPackagingFields;

/** Decodes authored package settings without applying artifact defaults. */
final class ManifestPackageDecoder {
    Optional<AuthoredPackage> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> modeField =
                index.field(FinalManifestPackagingFields.PACKAGE_MODE);
        Optional<ValidatedManifestField> sourcesField =
                index.field(FinalManifestPackagingFields.PACKAGE_SOURCES);
        Optional<ValidatedManifestField> javadocField =
                index.field(FinalManifestPackagingFields.PACKAGE_JAVADOC);
        Optional<ValidatedManifestField> testJarField =
                index.field(FinalManifestPackagingFields.PACKAGE_TEST_JAR);
        Optional<ValidatedManifestField> duplicatesField =
                index.field(FinalManifestPackagingFields.PACKAGE_DUPLICATES);
        if (modeField.isEmpty()
                && sourcesField.isEmpty()
                && javadocField.isEmpty()
                && testJarField.isEmpty()
                && duplicatesField.isEmpty()) {
            return Optional.empty();
        }

        Optional<AuthoredPackage.Mode> mode = modeField.map(ManifestPackageDecoder::mode);
        Optional<Boolean> sources = sourcesField.map(ManifestTomlValues::booleanValue);
        Optional<Boolean> javadoc = javadocField.map(ManifestTomlValues::booleanValue);
        Optional<Boolean> testJar = testJarField.map(ManifestTomlValues::booleanValue);
        Optional<AuthoredPackage.DuplicatePolicy> duplicates = duplicatesField.map(
                field -> duplicatePolicy(field, mode, sources, javadoc, testJar));
        ValidatedManifestField anchor = modeField
                .or(() -> sourcesField)
                .or(() -> javadocField)
                .or(() -> testJarField)
                .or(() -> duplicatesField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredPackage(mode, sources, javadoc, testJar, duplicates)));
    }

    private static AuthoredPackage.Mode mode(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        for (AuthoredPackage.Mode mode : AuthoredPackage.Mode.values()) {
            if (mode.configValue().equals(value)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "Final manifest schema accepted package mode `" + value
                        + "` at `" + field.path() + "` but the model does not recognize it.");
    }

    private static AuthoredPackage.DuplicatePolicy duplicatePolicy(
            ValidatedManifestField field,
            Optional<AuthoredPackage.Mode> mode,
            Optional<Boolean> sources,
            Optional<Boolean> javadoc,
            Optional<Boolean> testJar) {
        AuthoredPackage.DuplicatePolicy policy = duplicatePolicy(field);
        ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredPackage(
                        mode,
                        sources,
                        javadoc,
                        testJar,
                        Optional.of(policy)));
        return policy;
    }

    private static AuthoredPackage.DuplicatePolicy duplicatePolicy(
            ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        for (AuthoredPackage.DuplicatePolicy policy
                : AuthoredPackage.DuplicatePolicy.values()) {
            if (policy.configValue().equals(value)) {
                return policy;
            }
        }
        throw new IllegalStateException(
                "Final manifest schema accepted package duplicate policy `" + value
                        + "` at `" + field.path() + "` but the model does not recognize it.");
    }
}
