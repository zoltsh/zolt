package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageServiceTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.PublicationMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageEvidenceFreshnessTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void evidenceTracksCurrentSourcesResourcesGeneratedSourcesAndApplicationBytes()
            throws IOException {
        writeLockfile(projectDir);
        String mainSource = """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """;
        String resource = "name=original\n";
        String generatedSource = """
                package com.example.generated;

                public interface GeneratedApi {
                }
                """;
        Path main = projectDir.resolve(
                "src/main/java/com/example/Main.java");
        Path resourcePath = projectDir.resolve(
                "src/main/resources/application.properties");
        Path generated = projectDir.resolve(
                "target/generated/sources/openapi/com/example/generated/GeneratedApi.java");
        source(projectDir, "src/main/java/com/example/Main.java", mainSource);
        source(
                projectDir,
                "src/main/resources/application.properties",
                resource);
        source(projectDir, "src/main/openapi/api.yaml", "openapi: 3.1.0\n");
        source(
                projectDir,
                "target/generated/sources/openapi/com/example/generated/GeneratedApi.java",
                generatedSource);
        BuildSettings build = BuildSettings.defaults().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false)),
                List.of());
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withBuildSettings(build);
        PackageResult result = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));
        Path evidence = result.evidenceManifestPath().orElseThrow();

        Files.writeString(main, mainSource + "\n// changed\n");
        assertStale(config, evidence, "build inputs changed");
        Files.writeString(main, mainSource);

        Files.writeString(resourcePath, "name=changed\n");
        assertStale(config, evidence, "build inputs changed");
        Files.writeString(resourcePath, resource);

        Files.writeString(generated, generatedSource + "\n// changed\n");
        assertStale(config, evidence, "build inputs changed");
        Files.writeString(generated, generatedSource);

        Path applicationClass =
                projectDir.resolve("target/classes/com/example/Main.class");
        Files.writeString(applicationClass, "changed application bytecode");
        assertStale(config, evidence, "application output changed");
    }

    @Test
    void testsArtifactEvidenceTracksTestSourcesAndCompiledTestOutput()
            throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        Path testSource = projectDir.resolve(
                "src/test/java/com/example/MainTest.java");
        source(projectDir, "src/test/java/com/example/MainTest.java", """
                package com.example;
                final class MainTest {
                }
                """);
        Path testClass = projectDir.resolve(
                "target/test-classes/com/example/MainTest.class");
        Files.createDirectories(testClass.getParent());
        Files.writeString(testClass, "test bytecode");
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(
                        PackageMode.THIN,
                        false,
                        false,
                        true,
                        PublicationMetadata.empty()));
        PackageResult result = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));
        Path evidence = result.evidenceManifestPath().orElseThrow();

        Files.writeString(testSource, Files.readString(testSource) + "\n// changed\n");
        assertStale(config, evidence, "supplemental package input `tests` changed");
        Files.writeString(testSource, """
                package com.example;
                final class MainTest {
                }
                """);

        Files.writeString(testClass, "changed test bytecode");
        assertStale(config, evidence, "supplemental package input `tests` changed");
    }

    @Test
    void sourcesAndJavadocEvidenceTrackTheirLiveSourceInputs()
            throws IOException {
        writeLockfile(projectDir);
        Path main = projectDir.resolve(
                "src/main/java/com/example/Main.java");
        String source = """
                package com.example;

                /** Application entry point. */
                public final class Main {
                    private Main() {
                    }

                    /**
                     * Runs the application.
                     *
                     * @param args command-line arguments
                     */
                    public static void main(String[] args) {
                    }
                }
                """;
        source(projectDir, "src/main/java/com/example/Main.java", source);
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(
                        PackageMode.THIN,
                        true,
                        true,
                        false,
                        PublicationMetadata.empty()));
        PackageResult result = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));
        Path evidence = result.evidenceManifestPath().orElseThrow();

        Files.writeString(main, source + "\n// changed\n");
        assertStale(config, evidence, "supplemental package input `sources` changed");
        assertStale(config, evidence, "supplemental package input `javadoc` changed");
    }

    private void assertStale(
            ProjectConfig config,
            Path evidence,
            String expectedProblem) {
        PackagePlan current = new PackagePlanService().plan(
                projectDir,
                config);
        List<String> problems = new PackageEvidenceVerifier()
                .verify(projectDir, current, evidence)
                .problems();
        assertTrue(
                problems.stream().anyMatch(problem ->
                        problem.contains(expectedProblem)),
                problems.toString());
    }
}
