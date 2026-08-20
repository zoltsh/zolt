package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.schema.FinalManifestTestFields;

/** Decodes custom unit and integration test roots without applying conventional defaults. */
final class ManifestTestRootsDecoder {
    Optional<AuthoredTests.Sources> decodeSources(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> javaField =
                index.field(FinalManifestTestFields.TEST_SOURCES_JAVA);
        Optional<ValidatedManifestField> groovyField =
                index.field(FinalManifestTestFields.TEST_SOURCES_GROOVY);
        if (javaField.isEmpty() && groovyField.isEmpty()) {
            return Optional.empty();
        }

        List<ManifestRelativePath> java = javaField
                .map(field -> paths(
                        field, prefix -> new AuthoredTests.Sources(prefix, List.of())))
                .orElse(List.of());
        List<ManifestRelativePath> groovy = groovyField
                .map(field -> paths(
                        field, prefix -> new AuthoredTests.Sources(List.of(), prefix)))
                .orElse(List.of());
        ValidatedManifestField anchor = javaField.orElseGet(groovyField::orElseThrow);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredTests.Sources(java, groovy)));
    }

    Optional<AuthoredTests.Integration> decodeIntegration(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> sourcesField =
                index.field(FinalManifestTestFields.TEST_INTEGRATION_SOURCES);
        Optional<ValidatedManifestField> resourcesField =
                index.field(FinalManifestTestFields.TEST_INTEGRATION_RESOURCES);
        if (sourcesField.isEmpty() && resourcesField.isEmpty()) {
            return Optional.empty();
        }

        List<ManifestRelativePath> sources = sourcesField
                .map(field -> paths(
                        field, prefix -> new AuthoredTests.Integration(prefix, List.of())))
                .orElse(List.of());
        List<ManifestRelativePath> resources = resourcesField
                .map(field -> paths(
                        field, prefix -> new AuthoredTests.Integration(List.of(), prefix)))
                .orElse(List.of());
        ValidatedManifestField anchor = sourcesField.orElseGet(resourcesField::orElseThrow);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredTests.Integration(sources, resources)));
    }

    private static List<ManifestRelativePath> paths(
            ValidatedManifestField field,
            Function<List<ManifestRelativePath>, Object> probe) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<ManifestRelativePath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new ManifestRelativePath(authored.get(index))));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> probe.apply(paths));
        }
        return List.copyOf(paths);
    }
}
