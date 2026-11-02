package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.build.CompilationSemantics;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageBuildInputFingerprintTest {
    @TempDir
    private Path projectRoot;

    @Test
    void producerFingerprintsAreAuthoritativeAndOrderIndependent() {
        GeneratedSourceProducerFingerprint alpha =
                producer("alpha", "producer-a");
        GeneratedSourceProducerFingerprint beta =
                producer("beta", "producer-b");

        String initial = fingerprint(List.of(alpha, beta));
        String reversed = fingerprint(List.of(beta, alpha));
        String changed = fingerprint(List.of(
                alpha,
                producer("beta", "producer-b-changed")));

        assertEquals(initial, reversed);
        assertNotEquals(initial, changed);
    }

    @Test
    void generatedStepDeclarationOrderDoesNotChangePackageFingerprint() {
        List<GeneratedSourceProducerFingerprint> producers =
                List.of(
                        producer("alpha", "producer-a"),
                        producer("beta", "producer-b"));

        assertEquals(
                fingerprint(config("alpha", "beta"), producers),
                fingerprint(config("beta", "alpha"), producers));
    }

    @Test
    void testOnlyBuildSettingsDoNotChangeMainPackageFingerprint() {
        assertEquals(
                fingerprint(testConfig("fixtures-v1.sql"), List.of()),
                fingerprint(testConfig("fixtures-v2.sql"), List.of()));
    }

    @Test
    void compilationSemanticsVersionInvalidatesPackageEvidenceIdentity() {
        assertNotEquals(
                fingerprint(config(), List.of(), "4"),
                fingerprint(config(), List.of(), CompilationSemantics.VERSION));
    }

    @Test
    void groovyMainSourceChangesBuildAndSourcesFingerprintsButNotJavadocSources()
            throws IOException {
        Path groovy = projectRoot.resolve("src/main/java/com/example/GroovyApi.groovy");
        Files.createDirectories(groovy.getParent());
        Files.writeString(groovy, "package com.example\nclass GroovyApi {}\n");
        ProjectConfig config = config().withPackageSettings(new PackageSettings(
                PackageMode.THIN,
                true,
                true,
                false,
                PublicationMetadata.empty()));

        String buildBefore = fingerprint(config, List.of());
        List<PackagePlanLiveInput> supplementalBefore = supplementalInputs(config);

        Files.writeString(groovy, "package com.example\nclass GroovyApi { int changed }\n");

        assertNotEquals(buildBefore, fingerprint(config, List.of()));
        List<PackagePlanLiveInput> supplementalAfter = supplementalInputs(config);
        assertNotEquals(
                supplementalFingerprint(supplementalBefore, "sources"),
                supplementalFingerprint(supplementalAfter, "sources"));
        assertEquals(
                supplementalFingerprint(supplementalBefore, "javadoc"),
                supplementalFingerprint(supplementalAfter, "javadoc"));
    }

    @Test
    void kotlinMainSourceChangesBuildAndSourcesFingerprintsButNotJavadocSources()
            throws IOException {
        Path kotlin = projectRoot.resolve("src/main/java/com/example/KotlinApi.kt");
        Files.createDirectories(kotlin.getParent());
        Files.writeString(kotlin, "package com.example\nclass KotlinApi\n");
        ProjectConfig config = config().withPackageSettings(new PackageSettings(
                PackageMode.THIN,
                true,
                true,
                false,
                PublicationMetadata.empty()));

        String buildBefore = fingerprint(config, List.of());
        List<PackagePlanLiveInput> supplementalBefore = supplementalInputs(config);

        Files.writeString(kotlin, "package com.example\nclass KotlinApi(val changed: Int)\n");

        assertNotEquals(buildBefore, fingerprint(config, List.of()));
        List<PackagePlanLiveInput> supplementalAfter = supplementalInputs(config);
        assertNotEquals(
                supplementalFingerprint(supplementalBefore, "sources"),
                supplementalFingerprint(supplementalAfter, "sources"));
        assertEquals(
                supplementalFingerprint(supplementalBefore, "javadoc"),
                supplementalFingerprint(supplementalAfter, "javadoc"));
    }

    @Test
    void groovyFilesUnderResourceRootsDoNotChangeBuildFingerprint()
            throws IOException {
        Path groovy = projectRoot.resolve("src/main/resources/com/example/NotAResource.groovy");
        Files.createDirectories(groovy.getParent());
        Files.writeString(groovy, "class NotAResource {}\n");

        String before = fingerprint(config(), List.of());
        Files.writeString(groovy, "class NotAResource { int changed }\n");

        assertEquals(before, fingerprint(config(), List.of()));
    }

    @Test
    void kotlinFilesUnderResourceRootsDoNotChangeBuildFingerprint() throws IOException {
        Path kotlin = projectRoot.resolve("src/main/resources/com/example/NotAResource.kt");
        Files.createDirectories(kotlin.getParent());
        Files.writeString(kotlin, "class NotAResource\n");

        String before = fingerprint(config(), List.of());
        Files.writeString(kotlin, "class NotAResource(val changed: Int)\n");

        assertEquals(before, fingerprint(config(), List.of()));
    }

    @Test
    void kspScratchOutputsDoNotChangePackageFingerprint() throws IOException {
        ProjectConfig config = kspConfig();
        Path output = projectRoot.resolve("target/generated/ksp/main/symbols");
        write(output.resolve("cache/lookups.bin"), "cache-before");
        write(output.resolve("classes/p/Scratch.class"), "classes-before");

        String before = fingerprint(config, List.of());
        write(output.resolve("cache/lookups.bin"), "cache-after");
        write(output.resolve("classes/p/Scratch.class"), "classes-after");

        assertEquals(before, fingerprint(config, List.of()));
    }

    @Test
    void kspPublishedOutputsChangePackageFingerprint() throws IOException {
        ProjectConfig config = kspConfig();
        Path output = projectRoot.resolve("target/generated/ksp/main/symbols");
        Path java = output.resolve("java/p/Generated.java");
        Path kotlin = output.resolve("kotlin/p/Generated.kt");
        Path resource = output.resolve("resources/META-INF/generated.txt");
        write(java, "java-before");
        write(kotlin, "kotlin-before");
        write(resource, "resource-before");
        String before = fingerprint(config, List.of());

        write(java, "java-after");
        assertNotEquals(before, fingerprint(config, List.of()));
        write(java, "java-before");
        write(kotlin, "kotlin-after");
        assertNotEquals(before, fingerprint(config, List.of()));
        write(kotlin, "kotlin-before");
        write(resource, "resource-after");
        assertNotEquals(before, fingerprint(config, List.of()));
    }

    @Test
    void testsSupplementalFingerprintReadsKotlinOnlyFromConfiguredKotlinRoots()
            throws IOException {
        Path configured = projectRoot.resolve("src/test/kotlin/com/example/ConfiguredTest.kt");
        Files.createDirectories(configured.getParent());
        Files.writeString(configured, "package com.example\nclass ConfiguredTest\n");
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [package]
                testJar = true
                """);

        String before = supplementalFingerprint(supplementalInputs(config), "tests");
        Files.writeString(configured, "package com.example\nclass ConfiguredTest(val changed: Int)\n");
        String configuredChanged = supplementalFingerprint(supplementalInputs(config), "tests");

        assertNotEquals(before, configuredChanged);

        Path misplaced = projectRoot.resolve("src/test/java/com/example/MisplacedTest.kt");
        Files.createDirectories(misplaced.getParent());
        Files.writeString(misplaced, "package com.example\nclass MisplacedTest\n");

        assertEquals(
                configuredChanged,
                supplementalFingerprint(supplementalInputs(config), "tests"));
    }

    @Test
    void effectiveResourceTokensAreCanonicalByName() {
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [resources.tokens]
                project-version = { project = "version" }
                enterprise-platform-version = { value = "2026.06" }
                """);

        assertEquals(
                List.of("enterprise-platform-version", "project-version"),
                List.copyOf(PackageBuildInputFingerprint
                        .effectiveResourceTokens(config)
                        .keySet()));
    }

    private String fingerprint(
            List<GeneratedSourceProducerFingerprint> producers) {
        return fingerprint(config(), producers);
    }

    private List<PackagePlanLiveInput> supplementalInputs(ProjectConfig config) {
        return PackageSupplementalInputFingerprint.inputs(
                projectRoot,
                config,
                "build-input",
                "application-output",
                "package-lock",
                List.of());
    }

    private static String supplementalFingerprint(
            List<PackagePlanLiveInput> inputs,
            String kind) {
        return inputs.stream()
                .filter(input -> kind.equals(input.kind()))
                .findFirst()
                .orElseThrow()
                .fingerprint();
    }

    private String fingerprint(
            ProjectConfig projectConfig,
            List<GeneratedSourceProducerFingerprint> producers) {
        return PackageBuildInputFingerprint.fingerprint(
                projectRoot,
                projectConfig,
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()),
                List.of(),
                producers);
    }

    private String fingerprint(
            ProjectConfig projectConfig,
            List<GeneratedSourceProducerFingerprint> producers,
            String compilationSemantics) {
        return PackageBuildInputFingerprint.fingerprint(
                projectRoot,
                projectConfig,
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()),
                List.of(),
                producers,
                compilationSemantics);
    }

    private static ProjectConfig config(
            String first,
            String second) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.main.%s]
                kind = "declared-root"
                language = "java"
                inputs = ["schemas/%s.json"]
                output = "target/generated/%s"

                [generated.main.%s]
                kind = "declared-root"
                language = "java"
                inputs = ["schemas/%s.json"]
                output = "target/generated/%s"
                """.formatted(
                        first,
                        first,
                        first,
                        second,
                        second,
                        second));
    }

    private static GeneratedSourceProducerFingerprint producer(
            String stepId,
            String fingerprint) {
        return new GeneratedSourceProducerFingerprint(
                "main",
                stepId,
                GeneratedSourceKind.EXEC,
                fingerprint);
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
    }

    private static ProjectConfig kspConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.ksp]
                version = "2.2.0-2.0.2"
                coordinates = [
                    { coordinate = "com.example:fixture-processor", version = "1.0.0" },
                ]

                [generated.main.symbols]
                kind = "ksp"
                """);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static ProjectConfig testConfig(String input) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["%s"]
                output = "target/generated/test-fixtures"
                """.formatted(input));
    }
}
