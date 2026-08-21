package sh.zolt.toml.manifest.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.ManifestField;

/** Shared canonical values for the authored generated-source domain. */
final class ManifestGeneratedWriterValues {
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private ManifestGeneratedWriterValues() {
    }

    static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    static <T> String strings(
            ManifestField field, List<T> values, Function<T, String> text) {
        return ManifestTomlValueEncoder.fieldArray(field, values.stream()
                .map(text)
                .map(ManifestGeneratedWriterValues::string)
                .toList());
    }

    static String stringMap(Map<String, String> values) {
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        values.forEach((key, value) -> members.add(dynamicMember(key, string(value))));
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    static <T> String environmentMap(
            Map<EnvironmentVariableName, T> values, Function<T, String> text) {
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        values.forEach((key, value) -> members.add(ManifestTomlValueEncoder.member(
                key.value(), string(text.apply(value)))));
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    static String artifactRequests(
            ManifestField field, List<GeneratedArtifactRequest> requests) {
        return ManifestTomlValueEncoder.fieldArray(field, requests.stream()
                .map(ManifestGeneratedWriterValues::artifactRequest)
                .toList());
    }

    private static String artifactRequest(GeneratedArtifactRequest request) {
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        members.add(ManifestTomlValueEncoder.member(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_COORDINATE.name(),
                string(request.coordinate().value())));
        switch (request.selector()) {
            case DependencySelector.FixedVersion fixed -> members.add(
                    ManifestTomlValueEncoder.member(
                            FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION.name(),
                            string(fixed.value())));
            case DependencySelector.VersionReference reference -> members.add(
                    ManifestTomlValueEncoder.member(
                            FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION_REF.name(),
                            string(reference.alias().value())));
            default -> throw new IllegalStateException(
                    "Authored generated-tool artifact has an unsupported selector.");
        }
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static ManifestTomlValueEncoder.InlineMember dynamicMember(
            String key, String encodedValue) {
        return BARE_KEY.matcher(key).matches()
                ? ManifestTomlValueEncoder.member(key, encodedValue)
                : ManifestTomlValueEncoder.quotedMember(key, encodedValue);
    }
}
