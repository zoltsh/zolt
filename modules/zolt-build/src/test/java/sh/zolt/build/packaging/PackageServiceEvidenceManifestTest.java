package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.readEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageServiceTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.provenance.GitProvenance;
import sh.zolt.resolve.ResolveService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceEvidenceManifestTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void writesDeterministicPackageEvidenceManifestWithoutResourceTokenValues() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        source(projectDir, "src/main/resources/application.properties", "name=@projectName@\nsecret=@secretToken@\n");
        source(projectDir, "src/main/openapi/api.yaml", "openapi: 3.1.0\n");
        source(projectDir, "target/generated/sources/openapi/com/example/generated/GeneratedApi.java", """
                package com.example.generated;

                public interface GeneratedApi {
                }
                """);
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of(
                        "projectName", ResourceTokenSettings.project("name"),
                        "secretToken", ResourceTokenSettings.literal("super-secret-value")));
        BuildSettings build = BuildSettings.defaults()
                .withResourceFiltering(filtering)
                .withGeneratedSources(
                        List.of(new GeneratedSourceStep(
                                "openapi",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/sources/openapi",
                                List.of("src/main/openapi/api.yaml"),
                                true,
                                false)),
                        List.of());
        build = withMetadata(build, new BuildMetadataSettings(false, false, true));
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withBuildSettings(build);

        PackageResult first = packageService.packageJar(projectDir, config, projectDir.resolve("cache"));
        String firstEvidence = Files.readString(first.evidenceManifestPath().orElseThrow());
        PackageResult second = packageService.packageJar(projectDir, config, projectDir.resolve("cache"));
        String secondEvidence = Files.readString(second.evidenceManifestPath().orElseThrow());

        assertEquals(firstEvidence, secondEvidence);
        assertEquals(
                projectDir.resolve("target/demo-0.1.0.jar.zolt-package.json"),
                first.evidenceManifestPath().orElseThrow());
        assertTrue(firstEvidence.contains("\"schema\": \"zolt.package-evidence.v3\""));
        assertTrue(firstEvidence.contains("\"inputFingerprint\": \"sha256:"));
        assertTrue(firstEvidence.contains("\"outputs\": ["));
        assertTrue(firstEvidence.contains("\"archive\": \"target/demo-0.1.0.jar\""));
        assertTrue(firstEvidence.contains("\"archiveSha256\": \"sha256:"));
        assertTrue(firstEvidence.contains("\"resourceFiltering\": {"));
        assertTrue(firstEvidence.contains("\"source\": \"literal\""));
        assertTrue(firstEvidence.contains("\"source\": \"project\""));
        assertTrue(firstEvidence.contains("\"path\": \"src/main/resources/application.properties\""));
        assertTrue(firstEvidence.contains("\"generatedSources\": ["));
        assertTrue(firstEvidence.contains("\"id\": \"generated-main-openapi\""));
        assertTrue(firstEvidence.contains("\"path\": \"src/main/openapi/api.yaml\""));
        assertTrue(firstEvidence.contains("\"freshness\": \"fresh\""));
        assertTrue(firstEvidence.contains("\"toolVersionRef\": null"));
        assertFalse(firstEvidence.contains("super-secret-value"));
    }

    @Test
    void packageOutputsIncludeZoltBuilderProvenanceWhenSupplied() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withBuildSettings(withMetadata(
                        BuildSettings.defaults(),
                        new BuildMetadataSettings(true, false, true)));
        PackageService packageService = new PackageService(
                new ResolveService(),
                FrameworkPackageAugmenter.none(),
                new PackagePlanService(),
                provenanceSource());

        PackageResult result = packageService.packageJar(projectDir, config, projectDir.resolve("cache"));

        try (JarFile jar = new JarFile(result.jarPath().toFile())) {
            assertEquals(
                    "Zolt 0.1.0-zap.20260707.abcdef123456",
                    jar.getManifest().getMainAttributes().getValue("Created-By"));
            assertEquals(
                    "0.1.0-zap.20260707.abcdef123456",
                    jar.getManifest().getMainAttributes().getValue("Zolt-Version"));
            assertEquals(
                    "sha256:package-inputs",
                    jar.getManifest().getMainAttributes().getValue("Zolt-Resolution-Fingerprint"));
            assertTrue(readEntry(jar, "META-INF/build-info.properties")
                    .contains("build.tool.version=0.1.0-zap.20260707.abcdef123456"));
        }
        String evidence = Files.readString(result.evidenceManifestPath().orElseThrow());
        assertTrue(evidence.contains("\"builder\": {"), evidence);
        assertTrue(evidence.contains("\"version\": \"0.1.0-zap.20260707.abcdef123456\""), evidence);
        assertTrue(evidence.contains("\"resolutionFingerprint\": \"sha256:package-inputs\""), evidence);
    }

    private static BuildSettings withMetadata(
            BuildSettings build,
            BuildMetadataSettings metadata) {
        return new BuildSettings(
                build.source(),
                build.sourceRoots(),
                build.test(),
                build.outputRoot(),
                build.output(),
                build.testOutput(),
                build.testSources(),
                build.groovyTestSources(),
                build.integrationTestOutput(),
                build.integrationTestSources(),
                build.integrationTestResourceRoots(),
                build.resourceRoots(),
                build.testResourceRoots(),
                build.resourceFiltering(),
                build.testRuntime(),
                build.testSuites(),
                metadata,
                build.generatedMainSources(),
                build.generatedTestSources());
    }

    @Test
    void recordsOpenApiToolVersionRefInPackageEvidenceManifest() throws IOException {
        source(projectDir, "src/main/openapi/api.yaml", "openapi: 3.1.0\n");
        Path output = projectDir.resolve("target/generated/sources/openapi/com/example/generated/GeneratedApi.java");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "package com.example.generated; public interface GeneratedApi {}\n");
        Path classes = projectDir.resolve("target/classes");
        Files.createDirectories(classes);
        Path archive = projectDir.resolve("target/demo-0.1.0.jar");
        Files.writeString(archive, "archive", StandardCharsets.UTF_8);

        BuildSettings build = BuildSettings.defaults().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.OPENAPI,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false,
                        new OpenApiGenerationSettings(
                                Optional.of("org.openapitools:openapi-generator-cli"),
                                Optional.of("7.11.0"),
                                Optional.of("openapi-generator"),
                                Optional.empty(),
                                Optional.of("spring"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of()))),
                List.of());
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withBuildSettings(build);
        BuildResult buildResult = new BuildResult(Optional.empty(), 0, 0, classes, "");
        PackagePlan plan = new PackagePlan(
                projectDir,
                PackageMode.THIN,
                archive,
                classes,
                "classes-root",
                Optional.empty(),
                List.of(),
                List.of());
        PackageResult result = new PackageResult(
                buildResult,
                PackageMode.THIN,
                archive,
                Optional.empty(),
                Optional.empty(),
                1,
                true,
                List.of());

        PackageEvidenceManifestWriter writer = new PackageEvidenceManifestWriter();
        Path firstPath = writer.write(projectDir, config, plan, result, List.of());
        String firstEvidence = Files.readString(firstPath);
        Path secondPath = writer.write(projectDir, config, plan, result, List.of());
        String secondEvidence = Files.readString(secondPath);

        assertEquals(firstEvidence, secondEvidence);
        assertTrue(firstEvidence.contains("\"toolArtifact\": \"org.openapitools:openapi-generator-cli:7.11.0\""));
        assertTrue(firstEvidence.contains("\"toolVersionRef\": \"openapi-generator\""));
        assertTrue(firstEvidence.contains("\"toolFingerprint\": \""));
        assertTrue(firstEvidence.contains("\"optionsFingerprint\": \""));
    }

    private static BuildProvenanceSource provenanceSource() {
        return (projectRoot, environment, clock) -> new BuildProvenance(
                new GitProvenance(
                        Optional.of("0123456789abcdef0123456789abcdef01234567"),
                        Optional.of("0123456789ab"),
                        Optional.of("main"),
                        false,
                        Optional.empty()),
                Instant.parse("2026-07-07T12:00:00Z"),
                "0.1.0-zap.20260707.abcdef123456",
                "21.0.2",
                "Eclipse Adoptium",
                Optional.of("sha256:package-inputs"));
    }
}
