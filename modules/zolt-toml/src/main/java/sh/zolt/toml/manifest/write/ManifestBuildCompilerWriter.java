package sh.zolt.toml.manifest.write;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.toml.schema.FinalManifestBuildFields;
import sh.zolt.toml.schema.FinalManifestCompilerFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical authored build and compiler settings without materializing defaults. */
final class ManifestBuildCompilerWriter {
    private static final String DEFAULT_SOURCE = "src/main/java";
    private static final String DEFAULT_OUTPUT_ROOT = "target";
    private static final String DEFAULT_MAIN_OUTPUT = "classes";
    private static final String DEFAULT_TEST_OUTPUT = "test-classes";
    private static final String DEFAULT_INTEGRATION_OUTPUT = "integration-test-classes";
    private static final String DEFAULT_ENCODING = "UTF-8";

    private static final ManifestSection BUILD = section(FinalManifestPaths.BUILD);
    private static final ManifestSection BUILD_OUTPUT = section(FinalManifestPaths.BUILD_OUTPUT);
    private static final ManifestSection BUILD_METADATA = section(FinalManifestPaths.BUILD_METADATA);
    private static final ManifestSection COMPILER = section(FinalManifestPaths.COMPILER);
    private static final ManifestSection COMPILER_TEST = section(FinalManifestPaths.COMPILER_TEST);
    private static final ManifestSection COMPILER_GENERATED =
            section(FinalManifestPaths.COMPILER_GENERATED);

    void write(
            ManifestTomlEmitter emitter,
            Optional<AuthoredBuild> build,
            Optional<AuthoredCompiler> compiler) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(build, "Authored build settings are required.")
                .ifPresent(value -> writeBuild(emitter, value));
        Objects.requireNonNull(compiler, "Authored compiler settings are required.")
                .ifPresent(value -> writeCompiler(emitter, value));
    }

    private static void writeBuild(ManifestTomlEmitter emitter, AuthoredBuild build) {
        if (!build.sources().isEmpty() && !isConventionalSources(build.sources())) {
            emitter.section(BUILD);
            emitter.field(
                    FinalManifestBuildFields.BUILD_SOURCES,
                    paths(build.sources()));
        }
        build.output().ifPresent(value -> writeOutput(emitter, value));
        build.metadata().ifPresent(value -> writeMetadata(emitter, value));
    }

    private static void writeOutput(
            ManifestTomlEmitter emitter, AuthoredBuild.Output output) {
        emitter.section(BUILD_OUTPUT);
        writeNondefaultPath(
                emitter,
                FinalManifestBuildFields.BUILD_OUTPUT_ROOT,
                output.root(),
                DEFAULT_OUTPUT_ROOT);
        writeNondefaultPath(
                emitter,
                FinalManifestBuildFields.BUILD_OUTPUT_MAIN,
                output.main(),
                DEFAULT_MAIN_OUTPUT);
        writeNondefaultPath(
                emitter,
                FinalManifestBuildFields.BUILD_OUTPUT_TEST,
                output.test(),
                DEFAULT_TEST_OUTPUT);
        writeNondefaultPath(
                emitter,
                FinalManifestBuildFields.BUILD_OUTPUT_INTEGRATION,
                output.integration(),
                DEFAULT_INTEGRATION_OUTPUT);
    }

    private static void writeMetadata(
            ManifestTomlEmitter emitter, AuthoredBuild.Metadata metadata) {
        emitter.section(BUILD_METADATA);
        writeTrue(
                emitter,
                FinalManifestBuildFields.BUILD_METADATA_BUILD_INFO,
                metadata.buildInfo());
        writeTrue(
                emitter,
                FinalManifestBuildFields.BUILD_METADATA_GIT,
                metadata.git());
        writeTrue(
                emitter,
                FinalManifestBuildFields.BUILD_METADATA_REPRODUCIBLE,
                metadata.reproducible());
    }

    private static void writeCompiler(
            ManifestTomlEmitter emitter, AuthoredCompiler compiler) {
        emitter.section(COMPILER);
        compiler.encoding()
                .filter(value -> !DEFAULT_ENCODING.equals(value))
                .ifPresent(value -> emitter.field(
                        FinalManifestCompilerFields.COMPILER_ENCODING,
                        string(value)));
        compiler.jdkApi()
                .filter(value -> value != AuthoredCompiler.JdkApiMode.RELEASE)
                .ifPresent(value -> emitter.field(
                        FinalManifestCompilerFields.COMPILER_JDK_API,
                        string(value.configValue())));
        if (!compiler.args().isEmpty()) {
            emitter.field(
                    FinalManifestCompilerFields.COMPILER_ARGS,
                    strings(compiler.args()));
        }

        AuthoredCompiler.JdkApiMode mainMode = compiler.jdkApi()
                .orElse(AuthoredCompiler.JdkApiMode.RELEASE);
        compiler.test().ifPresent(value -> writeTestCompiler(emitter, value, mainMode));
        compiler.generated().ifPresent(value -> writeGenerated(emitter, value));
    }

    private static void writeTestCompiler(
            ManifestTomlEmitter emitter,
            AuthoredCompiler.Test test,
            AuthoredCompiler.JdkApiMode inheritedMode) {
        emitter.section(COMPILER_TEST);
        test.jdkApi()
                .filter(value -> value != inheritedMode)
                .ifPresent(value -> emitter.field(
                        FinalManifestCompilerFields.COMPILER_TEST_JDK_API,
                        string(value.configValue())));
        if (!test.args().isEmpty()) {
            emitter.field(
                    FinalManifestCompilerFields.COMPILER_TEST_ARGS,
                    strings(test.args()));
        }
    }

    private static void writeGenerated(
            ManifestTomlEmitter emitter, AuthoredCompiler.Generated generated) {
        emitter.section(COMPILER_GENERATED);
        generated.main().ifPresent(value -> emitter.field(
                FinalManifestCompilerFields.COMPILER_GENERATED_MAIN,
                path(value)));
        generated.test().ifPresent(value -> emitter.field(
                FinalManifestCompilerFields.COMPILER_GENERATED_TEST,
                path(value)));
    }

    private static void writeNondefaultPath(
            ManifestTomlEmitter emitter,
            ManifestField field,
            Optional<ManifestRelativePath> path,
            String defaultValue) {
        path.filter(value -> !defaultValue.equals(value.value()))
                .ifPresent(value -> emitter.field(field, path(value)));
    }

    private static void writeTrue(
            ManifestTomlEmitter emitter,
            ManifestField field,
            Optional<Boolean> value) {
        value.filter(Boolean::booleanValue)
                .ifPresent(present -> emitter.field(
                        field, ManifestTomlValueEncoder.booleanValue(present)));
    }

    private static boolean isConventionalSources(List<ManifestRelativePath> sources) {
        return sources.size() == 1 && DEFAULT_SOURCE.equals(sources.getFirst().value());
    }

    private static String paths(List<ManifestRelativePath> values) {
        return ManifestTomlValueEncoder.array(values.stream()
                .map(ManifestBuildCompilerWriter::path)
                .toList());
    }

    private static String path(ManifestRelativePath value) {
        return string(value.value());
    }

    private static String strings(List<String> values) {
        return ManifestTomlValueEncoder.array(values.stream()
                .map(ManifestBuildCompilerWriter::string)
                .toList());
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
