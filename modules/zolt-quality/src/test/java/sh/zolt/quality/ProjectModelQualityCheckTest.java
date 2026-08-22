package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectModelQualityCheckTest extends QualityCheckServiceTestSupport {
    private final ProjectModelQualityCheck check = new ProjectModelQualityCheck();

    @TempDir
    private Path tempDir;

    @Test
    void rejectsParentEscapingGeneratedInputPathsBeforeFilesystemChecks() throws IOException {
        // The final manifest language rejects `..` in an authored path, so the escaping step is built
        // directly: the check still guards every non-manifest producer of a generated-source step.
        ProjectConfig parsed = parseProject(tempDir.resolve("escaping-generated-input"), "");
        ProjectConfig config = parsed.withBuildSettings(parsed.build().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "api",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/api",
                        List.of("../api.yaml"),
                        true,
                        false)),
                List.of()));

        QualityCheckResult result = check.check(Optional.empty(), tempDir.resolve("escaping-generated-input"), config).getFirst();

        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("[generated.main].api.inputs[0]", result.subject());
        assertEquals("Path `../api.yaml` must be project-relative and stay inside the project.", result.message());
    }

    @Test
    void warnsWhenLegacyBuildFilesShareDefaultTargetOutputRoot() throws IOException {
        Path projectDir = tempDir.resolve("legacy-target");
        ProjectConfig config = parseProject(projectDir, "");
        Files.writeString(projectDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(projectDir.resolve("pom.xml"), "<project />\n");

        List<QualityCheckResult> results = check.check(Optional.empty(), projectDir, config);

        assertEquals(QualityCheckStatus.PASSED, results.get(0).status());
        QualityCheckResult warning = results.get(1);
        assertEquals(QualityCheckStatus.WARNING, warning.status());
        assertEquals("[build.output].root", warning.subject());
        assertTrue(warning.message().contains("Maven or Gradle project files are present (pom.xml, build.gradle)"));
        assertEquals(
                "For side-by-side migration, set [build.output].root = \".zolt/build\" in zolt.toml so Zolt-owned outputs stay separate.",
                warning.nextStep());
    }

    @Test
    void reportsUnusedVersionAliasesInSortedOrderWithoutFlaggingReferencedAliases() throws IOException {
        ProjectConfig config = parseProject(tempDir.resolve("version-aliases"), """

                [versions]
                boot = "4.0.6"
                lombok = "1.18.36"
                openapi = "7.11.0"
                test-lombok = "1.18.36"
                tomcat = "10.1.40"
                used = "1.0.0"
                unused-b = "2.0.0"
                unused-a = "3.0.0"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "boot" }

                [dependencies]
                "org.example:lib" = { versionRef = "used" }

                [dependencies.processor]
                "org.projectlombok:lombok" = { versionRef = "lombok" }

                [dependencies.test-processor]
                "org.projectlombok:lombok" = { versionRef = "test-lombok" }

                [dependencies.constraints]
                "org.apache.tomcat.embed:tomcat-embed-core" = { versionRef = "tomcat" }

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """);

        List<QualityCheckResult> results = check.check(Optional.empty(), tempDir.resolve("version-aliases"), config);

        assertEquals(List.of(
                        "version-aliases|Project model is valid for Zolt-owned checks at "
                                + tempDir.resolve("version-aliases").toAbsolutePath().normalize()
                                + ".",
                        "[versions].unused-a|Version alias `unused-a` is declared but not referenced by any versionRef.",
                        "[versions].unused-b|Version alias `unused-b` is declared but not referenced by any versionRef."),
                results.stream()
                        .map(result -> result.subject() + "|" + result.message())
                        .toList());
        String rendered = QualityCheckFormatter.text(new QualityCheckReport(tempDir, false, results));
        assertFalse(rendered.contains("[versions].used"));
        assertFalse(rendered.contains("[versions].openapi"));
    }
}
