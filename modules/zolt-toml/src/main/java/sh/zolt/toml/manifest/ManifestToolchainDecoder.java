package sh.zolt.toml.manifest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toml.schema.FinalManifestToolchainFields;
import sh.zolt.toml.schema.ManifestField;

/** Decodes authored Zolt and Java toolchain requests without applying defaults or inheritance. */
final class ManifestToolchainDecoder {
    AuthoredToolchains decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return new AuthoredToolchains(
                decodeZolt(index), decodeMainJava(index), decodeTestJava(index));
    }

    private static Optional<ZoltVersionPin> decodeZolt(ManifestDecodeIndex index) {
        return index.field(FinalManifestToolchainFields.ZOLT_VERSION)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new ZoltVersionPin(ManifestTomlValues.string(field))));
    }

    private static Optional<AuthoredJavaToolchain> decodeMainJava(ManifestDecodeIndex index) {
        Optional<JavaFeatureRelease> version = release(
                index, FinalManifestToolchainFields.JAVA_VERSION);
        Optional<JavaDistribution> distribution = symbol(
                index,
                FinalManifestToolchainFields.JAVA_DISTRIBUTION,
                JavaDistribution::fromId);
        Optional<Set<JavaFeature>> features = features(index);
        Optional<ToolchainPolicy> policy = symbol(
                index,
                FinalManifestToolchainFields.JAVA_POLICY,
                ToolchainPolicy::fromId);
        if (version.isEmpty()
                && distribution.isEmpty()
                && features.isEmpty()
                && policy.isEmpty()) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = firstPresent(index, List.of(
                FinalManifestToolchainFields.JAVA_VERSION,
                FinalManifestToolchainFields.JAVA_DISTRIBUTION,
                FinalManifestToolchainFields.JAVA_FEATURES,
                FinalManifestToolchainFields.JAVA_POLICY));
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredJavaToolchain(
                        version, distribution, features, policy)));
    }

    private static Optional<AuthoredJavaTestToolchain> decodeTestJava(
            ManifestDecodeIndex index) {
        Optional<JavaFeatureRelease> version = release(
                index, FinalManifestToolchainFields.JAVA_TEST_VERSION);
        Optional<JavaDistribution> distribution = symbol(
                index,
                FinalManifestToolchainFields.JAVA_TEST_DISTRIBUTION,
                JavaDistribution::fromId);
        Optional<ToolchainPolicy> policy = symbol(
                index,
                FinalManifestToolchainFields.JAVA_TEST_POLICY,
                ToolchainPolicy::fromId);
        if (version.isEmpty() && distribution.isEmpty() && policy.isEmpty()) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = firstPresent(index, List.of(
                FinalManifestToolchainFields.JAVA_TEST_VERSION,
                FinalManifestToolchainFields.JAVA_TEST_DISTRIBUTION,
                FinalManifestToolchainFields.JAVA_TEST_POLICY));
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredJavaTestToolchain(version, distribution, policy)));
    }

    private static Optional<JavaFeatureRelease> release(
            ManifestDecodeIndex index,
            ManifestField handle) {
        return index.field(handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> new JavaFeatureRelease(checkedInteger(field))));
    }

    private static int checkedInteger(ValidatedManifestField field) {
        long value = ManifestTomlValues.integer(field);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Java feature release is outside the supported integer range.", failure);
        }
    }

    private static Optional<Set<JavaFeature>> features(ManifestDecodeIndex index) {
        return index.field(FinalManifestToolchainFields.JAVA_FEATURES)
                .map(field -> ManifestSemanticDiagnostics.construct(field, () -> {
                    List<String> values = ManifestTomlValues.strings(field);
                    List<JavaFeature> decoded = values.stream()
                            .map(value -> ManifestAuthoredSymbols.authored(
                                    field, value, JavaFeature::fromId))
                            .toList();
                    return Set.copyOf(new LinkedHashSet<>(decoded));
                }));
    }

    private static ValidatedManifestField firstPresent(
            ManifestDecodeIndex index,
            List<ManifestField> handles) {
        return handles.stream()
                .map(index::field)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Authored toolchain aggregate has no direct field evidence."));
    }

    private static <T> Optional<T> symbol(
            ManifestDecodeIndex index,
            ManifestField handle,
            Function<String, Optional<T>> modelLookup) {
        return index.field(handle).map(field -> ManifestAuthoredSymbols.authored(
                field, ManifestTomlValues.string(field), modelLookup));
    }
}
