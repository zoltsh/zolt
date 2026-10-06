package sh.zolt.toml.manifest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredGroovyToolchain;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredKotlinToolchain;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.project.toolchain.GroovyToolchainVersion;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.KotlinToolchainVersion;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestToolchainFields;
import sh.zolt.toml.schema.ManifestField;

/** Decodes authored Zolt, Java, Groovy, and Kotlin requests without applying defaults or inheritance. */
final class ManifestToolchainDecoder {
    AuthoredToolchains decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return new AuthoredToolchains(
                decodeZolt(index),
                decodeMainJava(index),
                decodeTestJava(index),
                decodeGroovy(index),
                decodeKotlin(index));
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
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.TOOLCHAIN_JAVA)
                        .orElseThrow(() -> new IllegalStateException(
                                "Authored toolchain aggregate has no direct field evidence.")),
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
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.TOOLCHAIN_JAVA_TEST)
                        .orElseThrow(() -> new IllegalStateException(
                                "Authored toolchain aggregate has no direct field evidence.")),
                () -> new AuthoredJavaTestToolchain(version, distribution, policy)));
    }

    private static Optional<AuthoredGroovyToolchain> decodeGroovy(ManifestDecodeIndex index) {
        return index.field(FinalManifestToolchainFields.GROOVY_VERSION)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new AuthoredGroovyToolchain(new GroovyToolchainVersion(
                                ManifestTomlValues.string(field)))));
    }

    private static Optional<AuthoredKotlinToolchain> decodeKotlin(ManifestDecodeIndex index) {
        Optional<ValidatedManifestField> versionField = index.field(
                FinalManifestToolchainFields.KOTLIN_VERSION);
        Optional<ValidatedManifestField> pluginsField = index.field(
                FinalManifestToolchainFields.KOTLIN_PLUGINS);
        if (versionField.isEmpty() && pluginsField.isEmpty()) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = versionField.orElseGet(pluginsField::orElseThrow);
        return Optional.of(ManifestSemanticDiagnostics.construct(anchor, () -> {
            if (versionField.isEmpty()) {
                throw new IllegalArgumentException(
                        "Kotlin toolchain version is required when compiler plugins are configured.");
            }
            LinkedHashSet<KotlinCompilerPlugin> plugins = new LinkedHashSet<>();
            for (String value : pluginsField.map(ManifestTomlValues::strings).orElseGet(List::of)) {
                KotlinCompilerPlugin plugin = KotlinCompilerPlugin.fromId(value)
                        .orElseThrow(() -> new IllegalStateException(
                                "Final manifest schema accepted unknown Kotlin compiler plugin `"
                                        + value + "`."));
                if (!plugins.add(plugin)) {
                    throw new IllegalArgumentException(
                            "Kotlin compiler plugin `" + value + "` is declared more than once.");
                }
            }
            return new AuthoredKotlinToolchain(
                    new KotlinToolchainVersion(ManifestTomlValues.string(versionField.orElseThrow())),
                    plugins);
        }));
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

    private static <T> Optional<T> symbol(
            ManifestDecodeIndex index,
            ManifestField handle,
            Function<String, Optional<T>> modelLookup) {
        return index.field(handle).map(field -> ManifestAuthoredSymbols.authored(
                field, ManifestTomlValues.string(field), modelLookup));
    }
}
