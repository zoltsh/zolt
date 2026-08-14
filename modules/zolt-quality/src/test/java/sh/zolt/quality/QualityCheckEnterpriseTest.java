package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualityCheckEnterpriseTest extends QualityCheckServiceTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void ciContextReportsEnterpriseBlockersInDeterministicOrder() throws IOException {
        Path projectDir = tempDir.resolve("enterprise-blockers");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "enterprise-blockers"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                company = { url = "https://repo.example.test/maven", credentials = "company-artifactory" }

                [repositoryCredentials.company-artifactory]
                usernameEnv = "ARTIFACTORY_USERNAME"
                passwordEnv = "ARTIFACTORY_ACCESS_TOKEN"

                [generated.main.openapi]
                kind = "declared-root"
                language = "java"
                output = "target/generated/sources/openapi"
                inputs = ["src/main/openapi/api.yaml"]
                required = true
                """);
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/api.yaml"), "openapi: 3.1.0\n");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:local-lib"
                version = "1.0.0"
                source = "local-overlay:maven-local"
                scope = "compile"
                direct = true
                jar = "overlays/maven-local/com/example/local-lib/1.0.0/local-lib-1.0.0.jar"
                dependencies = []
                """);

        QualityCheckReport report = check(
                projectDir,
                tempDir.resolve("cache"),
                Map.of("ARTIFACTORY_USERNAME", "ci-user"),
                QualityCheckContext.CI,
                List.of());

        assertEquals("error", report.status());
        List<QualityCheckResult> failures = report.checks().stream()
                .filter(check -> check.status() == QualityCheckStatus.FAILED)
                .toList();
        assertEquals(List.of(
                        "execution-context|com.example:local-lib:1.0.0|CI context rejects local repository overlay origin `local-overlay:maven-local`.",
                        "execution-context|[repositoryCredentials.company-artifactory]|CI context requires environment variable ARTIFACTORY_ACCESS_TOKEN for repository `company` credentials `company-artifactory` before resolve/build work starts.",
                        "lockfile|zolt.lock|Repository `company` requires credentials `company-artifactory`, but environment variables ARTIFACTORY_USERNAME, ARTIFACTORY_ACCESS_TOKEN are not set. Set the variables and retry. Secret values are never written to zolt.lock or command output.",
                        "generated-sources|[generated.main.openapi]|Generated source root `target/generated/sources/openapi` is missing."),
                failures.stream()
                        .map(check -> check.id() + "|" + check.subject() + "|" + check.message())
                        .toList());

        String json = QualityCheckFormatter.json(report);
        assertTrue(json.contains("\"blockers\":[{"));
        assertTrue(json.contains("\"nextStep\":\"Run `zolt resolve --locked --no-local-overlays` or refresh zolt.lock without local overlays.\""));
        assertTrue(json.contains("\"nextStep\":\"Set the named CI secret and rerun `zolt check --context ci`. Secret values are never printed.\""));
        assertTrue(json.contains("\"nextStep\":\"Run the generator that produces it, commit the generated sources, or remove [generated.main.openapi] until Zolt supports that generator.\""));
        assertFalse(json.contains("ci-user"));
    }

    @Test
    void ciContextRejectsUnsupportedGeneratedSourceLanguageBeforeChecksRun() throws IOException {
        Path projectDir = tempDir.resolve("unsupported-generated-language");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "unsupported-generated-language"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.main.openapi]
                kind = "declared-root"
                language = "kotlin"
                output = "target/generated/sources/openapi"
                inputs = ["src/main/openapi/api.yaml"]
                required = true
                """);

        QualityCheckReport report = check(
                projectDir,
                tempDir.resolve("cache"),
                Map.<String, String>of(),
                QualityCheckContext.CI,
                List.of());

        assertEquals("error", report.status());
        assertEquals(List.of(
                        "execution-context|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "lockfile|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "project-model|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "dependency-metadata|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "dependency-policy|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "license-policy|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "generated-sources|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java.",
                        "package-contents|zolt.toml|Unsupported generated source language `kotlin` in zolt.toml. Supported generated source languages are: java."),
                report.checks().stream()
                        .map(check -> check.id() + "|" + check.subject() + "|" + check.message())
                        .toList());
        assertTrue(QualityCheckFormatter.text(report).contains("next: Fix zolt.toml, then run `zolt check` again."));
    }
}
