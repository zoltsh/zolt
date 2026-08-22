package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.generated.GeneratedSourceEvidenceService;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedSourceQualityCheckTest extends QualityCheckServiceTestSupport {
    private final GeneratedSourceQualityCheck check = new GeneratedSourceQualityCheck(new GeneratedSourceEvidenceService());

    @TempDir
    private Path tempDir;

    @Test
    void reportsNoDeclaredSteps() throws IOException {
        ProjectConfig config = parseProject(tempDir.resolve("no-generated-sources"), "");

        List<QualityCheckResult> results = check.check(Optional.empty(), tempDir.resolve("no-generated-sources"), config);

        assertEquals(1, results.size());
        assertResult(
                results.getFirst(),
                QualityCheckStatus.PASSED,
                "no-generated-sources",
                "No declared generated-source steps require validation.",
                "");
    }

    @Test
    void passesForFreshDeclaredRootsAndReportsIdeSourceRoot() throws IOException {
        Path projectDir = tempDir.resolve("fresh-generated-sources");
        Path outputFile = projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java");
        Path inputFile = projectDir.resolve("src/main/openapi/api.yaml");
        Files.createDirectories(outputFile.getParent());
        Files.createDirectories(inputFile.getParent());
        Files.writeString(inputFile, "openapi: 3.1.0\n");
        Files.writeString(outputFile, "package com.example; public final class GeneratedApi {}\n");
        Files.setLastModifiedTime(inputFile, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(outputFile, FileTime.fromMillis(2_000));
        ProjectConfig config = parseProject(projectDir, generatedSourceConfig(
                "main",
                "openapi",
                "target/generated/sources/openapi",
                "src/main/openapi/api.yaml",
                true));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertEquals(QualityCheckStatus.PASSED, result.status());
        assertEquals("[generated.main.openapi]", result.subject());
        assertTrue(result.message().contains("Generated source root `target/generated/sources/openapi` is declared"));
        assertTrue(result.message().contains("exported as IDE source root `generated-main-openapi`"));
        assertTrue(result.message().contains("ownership `external-declared-root` and freshness `fresh`"));
    }

    @Test
    void reportsMissingRequiredOutputWithActionableStep() throws IOException {
        Path projectDir = tempDir.resolve("missing-required-output");
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/api.yaml"), "openapi: 3.1.0\n");
        ProjectConfig config = parseProject(projectDir, generatedSourceConfig(
                "main",
                "openapi",
                "target/generated/sources/openapi",
                "src/main/openapi/api.yaml",
                true));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.openapi]",
                "Generated source root `target/generated/sources/openapi` is missing.",
                "Run the generator that produces it, commit the generated sources, or remove [generated.main.openapi] until Zolt supports that generator.");
    }

    @Test
    void reportsMissingInputBeforeOutputFreshness() throws IOException {
        Path projectDir = tempDir.resolve("missing-input");
        Files.createDirectories(projectDir.resolve("target/generated/sources/openapi/com/example"));
        Files.writeString(
                projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java"),
                "package com.example; public final class GeneratedApi {}\n");
        ProjectConfig config = parseProject(projectDir, generatedSourceConfig(
                "main",
                "openapi",
                "target/generated/sources/openapi",
                "src/main/openapi/api.yaml",
                true));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.openapi]",
                "Generated source input `src/main/openapi/api.yaml` is missing.",
                "Create the input file or update [generated.main.openapi].inputs.");
    }

    @Test
    void skipsOptionalMissingOutput() throws IOException {
        Path projectDir = tempDir.resolve("optional-missing-output");
        Files.createDirectories(projectDir.resolve("src/test/fixtures"));
        Files.writeString(projectDir.resolve("src/test/fixtures/schema.json"), "{}\n");
        ProjectConfig config = parseProject(projectDir, generatedSourceConfig(
                "test",
                "fixtures",
                "target/generated/test-sources/fixtures",
                "src/test/fixtures/schema.json",
                false));

        QualityCheckResult result = check.check(Optional.of("modules/api"), projectDir, config).getFirst();

        assertEquals(Optional.of("modules/api"), result.member());
        assertResult(
                result,
                QualityCheckStatus.SKIPPED,
                "[generated.test.fixtures]",
                "Optional generated source root `target/generated/test-sources/fixtures` is missing.",
                "Generate it when needed, or set required = true if the root must exist for builds.");
    }

    @Test
    void reportsStaleOutputWhenInputIsNewer() throws IOException {
        Path projectDir = tempDir.resolve("stale-generated-sources");
        Path outputFile = projectDir.resolve("target/generated/sources/openapi/com/example/GeneratedApi.java");
        Path inputFile = projectDir.resolve("src/main/openapi/api.yaml");
        Files.createDirectories(outputFile.getParent());
        Files.createDirectories(inputFile.getParent());
        Files.writeString(outputFile, "package com.example; public final class GeneratedApi {}\n");
        Files.writeString(inputFile, "openapi: 3.1.0\n");
        Files.setLastModifiedTime(outputFile, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(inputFile, FileTime.fromMillis(2_000));
        ProjectConfig config = parseProject(projectDir, generatedSourceConfig(
                "main",
                "openapi",
                "target/generated/sources/openapi",
                "src/main/openapi/api.yaml",
                true));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.openapi]",
                "Generated source root `target/generated/sources/openapi` is stale; one or more declared inputs are newer.",
                "Regenerate the source root or update [generated.main.openapi].inputs.");
    }

    @Test
    void rejectsGeneratedPathsThatEscapeProjectRoot() throws IOException {
        // The final manifest language rejects `..` in an authored path, so the escaping step is built
        // directly: the check still guards every non-manifest producer of a generated-source step.
        ProjectConfig config = withGeneratedStep(
                tempDir.resolve("bad-generated-path"),
                "../generated/openapi",
                "src/main/openapi/api.yaml");

        QualityCheckResult result = check.check(Optional.empty(), tempDir.resolve("bad-generated-path"), config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.openapi].output",
                "Invalid generated source output path `../generated/openapi`.",
                "Use a project-relative path under the project directory.");
    }

    @Test
    void rejectsGeneratedInputPathsThatEscapeProjectRoot() throws IOException {
        ProjectConfig config = withGeneratedStep(
                tempDir.resolve("bad-generated-input"),
                "target/generated/sources/openapi",
                "../api.yaml");

        QualityCheckResult result = check.check(Optional.empty(), tempDir.resolve("bad-generated-input"), config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.openapi].inputs",
                "Invalid generated source inputs path `../api.yaml`.",
                "Use a project-relative path under the project directory.");
    }

    @Test
    void rejectsUnsupportedGeneratedSourceLanguageWithMvpNextStep() throws IOException {
        Path projectDir = tempDir.resolve("bad-generated-language");
        ProjectConfig parsed = parseProject(projectDir, "");
        ProjectConfig config = parsed.withBuildSettings(parsed.build().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "kotlin-api",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "kotlin",
                        "target/generated/sources/kotlin",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false)),
                List.of()));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.kotlin-api]",
                "Unsupported generated source language `kotlin`.",
                "Use language = \"java\" for MVP generated-source steps.");
    }

    @Test
    void passesForFreshExecStep() throws IOException {
        Path projectDir = tempDir.resolve("fresh-exec");
        Path inputFile = projectDir.resolve("src/main/jooq/config.xml");
        Path outputFile = projectDir.resolve("target/generated/sources/jooq/com/example/Model.java");
        Files.createDirectories(inputFile.getParent());
        Files.createDirectories(outputFile.getParent());
        Files.writeString(inputFile, "<configuration/>\n");
        Files.writeString(outputFile, "package com.example; public final class Model {}\n");
        Files.setLastModifiedTime(inputFile, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(outputFile, FileTime.fromMillis(2_000));
        ProjectConfig config = parseProject(projectDir, execConfig("java-sources"));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertEquals(QualityCheckStatus.PASSED, result.status());
        assertEquals("[generated.main.model]", result.subject());
    }

    @Test
    void reportsExecLaneMismatch() throws IOException {
        Path projectDir = tempDir.resolve("exec-lane-mismatch");
        ProjectConfig config = parseProject(projectDir, execConfig("test-sources"));

        QualityCheckResult result = check.check(Optional.empty(), projectDir, config).getFirst();

        assertResult(
                result,
                QualityCheckStatus.FAILED,
                "[generated.main.model]",
                "Exec step produces test-sources but is declared in the wrong lane.",
                "Move it to [generated.test.model] or set produces = \"java-sources\".");
    }

    @Test
    void requireOfflineReadyFailsForCacheNoneStep() throws IOException {
        Path projectDir = tempDir.resolve("exec-cache-none");
        Path inputFile = projectDir.resolve("src/main/jooq/config.xml");
        Path outputFile = projectDir.resolve("target/generated/sources/jooq/com/example/Model.java");
        Files.createDirectories(inputFile.getParent());
        Files.createDirectories(outputFile.getParent());
        Files.writeString(inputFile, "<configuration/>\n");
        Files.writeString(outputFile, "package com.example; public final class Model {}\n");
        Files.setLastModifiedTime(inputFile, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(outputFile, FileTime.fromMillis(2_000));
        ProjectConfig config = parseProject(projectDir, execConfig("java-sources").replace(
                "produces = \"java-sources\"", "produces = \"java-sources\"\ncache = \"none\""));

        QualityCheckResult offlineReady = check.check(Optional.empty(), projectDir, config, true).getFirst();
        QualityCheckResult normal = check.check(Optional.empty(), projectDir, config, false).getFirst();

        assertEquals(QualityCheckStatus.FAILED, offlineReady.status());
        assertTrue(offlineReady.message().contains("cache = \"none\""), offlineReady.message());
        assertEquals(QualityCheckStatus.PASSED, normal.status());
    }

    /** A generated step whose authored form the final language rejects, built through the model. */
    private static ProjectConfig withGeneratedStep(
            Path projectDir,
            String output,
            String input) throws IOException {
        ProjectConfig parsed = parseProject(projectDir, "");
        return parsed.withBuildSettings(parsed.build().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        output,
                        List.of(input),
                        true,
                        false)),
                List.of()));
    }

    private static String execConfig(String produces) {
        return """

                [versions]
                jooq = "3.19.15"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.main.model]
                kind = "exec"
                tool = "jooq"
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/jooq"
                produces = "%s"
                """.formatted(produces);
    }

    private static void assertResult(
            QualityCheckResult result,
            QualityCheckStatus status,
            String subject,
            String message,
            String nextStep) {
        assertEquals(QualityCheckService.GENERATED_SOURCES, result.id());
        assertEquals(status, result.status());
        assertEquals(subject, result.subject());
        assertEquals(message, result.message());
        assertEquals(nextStep, result.nextStep());
    }
}
