package sh.zolt.toml.manifest.write;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestToolchainFields;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits authored Zolt and Java toolchain requests without materializing defaults. */
final class ManifestToolchainWriter {
    private static final ManifestSection ZOLT = section(FinalManifestPaths.TOOLCHAIN_ZOLT);
    private static final ManifestSection JAVA = section(FinalManifestPaths.TOOLCHAIN_JAVA);
    private static final ManifestSection JAVA_TEST =
            section(FinalManifestPaths.TOOLCHAIN_JAVA_TEST);

    void write(ManifestTomlEmitter emitter, AuthoredToolchains toolchains) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        AuthoredToolchains authored =
                Objects.requireNonNull(toolchains, "Authored toolchains are required.");
        authored.zolt().ifPresent(value -> writeZolt(emitter, value));
        authored.mainJava().ifPresent(value -> writeJava(emitter, value));
        authored.testJava().ifPresent(value -> writeTestJava(emitter, value));
    }

    private static void writeZolt(ManifestTomlEmitter emitter, ZoltVersionPin zolt) {
        emitter.section(ZOLT);
        emitter.field(
                FinalManifestToolchainFields.ZOLT_VERSION,
                ManifestTomlValueEncoder.basicString(zolt.value()));
    }

    private static void writeJava(
            ManifestTomlEmitter emitter, AuthoredJavaToolchain java) {
        emitter.section(JAVA);
        java.version().ifPresent(value -> emitter.field(
                FinalManifestToolchainFields.JAVA_VERSION,
                ManifestTomlValueEncoder.integer(value.value())));
        java.distribution().ifPresent(value -> emitter.field(
                FinalManifestToolchainFields.JAVA_DISTRIBUTION,
                ManifestTomlValueEncoder.basicString(value.id())));
        java.features()
                .filter(values -> !values.isEmpty())
                .ifPresent(values -> emitter.field(
                        FinalManifestToolchainFields.JAVA_FEATURES, features(values)));
        java.policy().ifPresent(value -> emitter.field(
                FinalManifestToolchainFields.JAVA_POLICY,
                ManifestTomlValueEncoder.basicString(value.id())));
    }

    private static void writeTestJava(
            ManifestTomlEmitter emitter, AuthoredJavaTestToolchain java) {
        emitter.section(JAVA_TEST);
        java.version().ifPresent(value -> emitter.field(
                FinalManifestToolchainFields.JAVA_TEST_VERSION,
                ManifestTomlValueEncoder.integer(value.value())));
        java.distribution().ifPresent(value -> emitter.field(
                FinalManifestToolchainFields.JAVA_TEST_DISTRIBUTION,
                ManifestTomlValueEncoder.basicString(value.id())));
        java.policy().ifPresent(value -> emitter.field(
                FinalManifestToolchainFields.JAVA_TEST_POLICY,
                ManifestTomlValueEncoder.basicString(value.id())));
    }

    private static String features(Set<JavaFeature> values) {
        List<String> encoded = values.stream()
                .map(JavaFeature::id)
                .map(ManifestTomlValueEncoder::basicString)
                .toList();
        return ManifestTomlValueEncoder.array(encoded);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
