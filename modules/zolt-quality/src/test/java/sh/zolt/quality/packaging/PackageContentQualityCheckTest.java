package sh.zolt.quality.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckService;
import sh.zolt.quality.QualityCheckStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageContentQualityCheckTest extends PackageQualityCheckTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void packageContentsRequiresLockfile() throws IOException {
        Path projectDir = tempDir.resolve("contents-missing-lock");
        ProjectConfig config = parseProject(projectDir, "");

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertResult(
                result,
                QualityCheckService.PACKAGE_CONTENTS,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "Package content diagnostics require zolt.lock.",
                "Run `zolt resolve`.");
    }

    @Test
    void packageContentsReportsMalformedLockfileWithWorkspaceResolveAction() throws IOException {
        Path projectDir = tempDir.resolve("contents-malformed-lock");
        ProjectConfig config = parseProject(projectDir, "");
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 1

                [[package]]
                id = 42
                """);

        QualityCheckResult result = check.checkContents(
                Optional.of("modules/api"),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertEquals(QualityCheckService.PACKAGE_CONTENTS, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals(Optional.of("modules/api"), result.member());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("Invalid value type in zolt.lock"));
        assertEquals("Run `zolt resolve --workspace`.", result.nextStep());
    }

    @Test
    void packageContentsReportsWarContainerDependencyWarning() throws IOException {
        Path projectDir = tempDir.resolve("war-container-warning");
        ProjectConfig config = parseProject(projectDir, """

                [package]
                mode = "war"
                """);
        writePackagePlanLockfile(projectDir, false, true);

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertEquals(QualityCheckService.PACKAGE_CONTENTS, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("org.apache.tomcat.embed:tomcat-embed-core:10.1.40", result.subject());
        assertTrue(result.message().contains("Container-style dependency `org.apache.tomcat.embed:tomcat-embed-core:10.1.40` is packaged in WEB-INF/lib/tomcat-embed-core-10.1.40.jar"));
        assertEquals(
                "Move it to [provided.dependencies] when the servlet container supplies it, then run `zolt resolve`.",
                result.nextStep());
    }

    @Test
    void packageContentsFailsWhenFrameworkPackageRulesAreMissing()
            throws IOException {
        Path projectDir = tempDir.resolve("quarkus-missing-rules");
        ProjectConfig config = parseProject(projectDir, """

                [package]
                mode = "quarkus"
                """);
        writeLockfile(projectDir, """

                [[package]]
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar"
                dependencies = []
                """);

        List<QualityCheckResult> results = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false);

        assertTrue(results.stream().allMatch(
                result -> result.status() == QualityCheckStatus.FAILED));
        assertTrue(results.stream().anyMatch(result ->
                result.subject().equals("[package].mode")
                        && result.message().contains(
                                "requires framework-aware package plan rules")));
        assertTrue(results.stream().anyMatch(result ->
                result.subject().equals(
                        "io.quarkus:quarkus-rest:3.33.0")
                        && result.message().contains(
                                "framework-package-plan-rules-missing")));
    }

    @Test
    void packageContentsReportsDeterministicRuleDiagnosticsAndPolicyEffects() throws IOException {
        Path projectDir = tempDir.resolve("rule-diagnostics");
        ProjectConfig config = parseProject(projectDir, """

                [package]
                mode = "spring-boot-war"
                """);
        writePackagePlanLockfile(projectDir, true, false);

        List<QualityCheckResult> results = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false);

        assertEquals(List.of(
                        "rule-diagnostics|Package mode `spring-boot-war` has 7 dependency dispositions. 1 dependencies include dependency policy effects.",
                        "rule:dev-only-omitted|1 dependency uses package rule `dev-only-omitted` with scope `dev`, disposition `omitted`, and location `none`.",
                        "rule:processor-omitted|1 dependency uses package rule `processor-omitted` with scope `processor`, disposition `omitted`, and location `none`.",
                        "rule:spring-boot-war-loader-expanded|1 dependency uses package rule `spring-boot-war-loader-expanded` with scope `runtime`, disposition `loader`, and location `archive root`.",
                        "rule:spring-boot-war-provided-lib|1 dependency uses package rule `spring-boot-war-provided-lib` with scope `provided`, disposition `provided`, and location `WEB-INF/lib-provided/*`.",
                        "rule:spring-boot-war-runtime-lib|1 dependency uses package rule `spring-boot-war-runtime-lib` with scope `compile`, disposition `included`, and location `WEB-INF/lib/*`.",
                        "rule:spring-boot-war-runtime-lib|1 dependency uses package rule `spring-boot-war-runtime-lib` with scope `runtime`, disposition `included`, and location `WEB-INF/lib/*`. 1 includes dependency policy effects.",
                        "rule:test-omitted|1 dependency uses package rule `test-omitted` with scope `test`, disposition `omitted`, and location `none`."),
                results.stream()
                        .map(result -> result.subject() + "|" + result.message())
                        .toList());
    }

    @Test
    void packageContentsRequiresPackageArtifactWhenConfigured() throws IOException {
        Path projectDir = tempDir.resolve("require-package");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, "");

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                true).getFirst();

        assertResult(
                result,
                QualityCheckService.PACKAGE_CONTENTS,
                QualityCheckStatus.FAILED,
                "target/require-package-0.1.0.jar",
                "CI context requires the configured package artifact, but it is missing.",
                "Run `zolt package` before `zolt check --context ci --require-package`.");
    }

    @Test
    void packageContentsReportsMissingEvidenceForExistingArchive() throws IOException {
        Path projectDir = tempDir.resolve("missing-evidence");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, "");
        Path jar = projectDir.resolve("target/missing-evidence-0.1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar bytes\n");

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertResult(
                result,
                QualityCheckService.PACKAGE_CONTENTS,
                QualityCheckStatus.FAILED,
                "target/missing-evidence-0.1.0.jar",
                "Package artifact exists, but package evidence manifest is missing.",
                "Run `zolt package` to regenerate target/missing-evidence-0.1.0.jar.zolt-package.json.");
    }

    @Test
    void packageContentsReportsStaleEvidenceChecksum() throws IOException {
        Path projectDir = tempDir.resolve("stale-evidence");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, "");
        Path jar = projectDir.resolve("target/stale-evidence-0.1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar bytes\n");
        Files.writeString(projectDir.resolve("target/stale-evidence-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/stale-evidence-0.1.0.jar",
                  "archiveSha256": "sha256:not-the-current-archive"
                }
                """);

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertResult(
                result,
                QualityCheckService.PACKAGE_CONTENTS,
                QualityCheckStatus.FAILED,
                "target/stale-evidence-0.1.0.jar.zolt-package.json",
                "Package evidence manifest is stale for `target/stale-evidence-0.1.0.jar`.",
                "Run `zolt package` to regenerate the artifact and evidence manifest.");
    }

    @Test
    void packageContentsAcceptsCurrentEvidenceForExistingArchive() throws IOException {
        Path projectDir = tempDir.resolve("current-evidence");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, "");
        Path jar = projectDir.resolve("target/current-evidence-0.1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar bytes\n");
        Files.writeString(projectDir.resolve("target/current-evidence-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/current-evidence-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(sha256(jar)));

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertResult(
                result,
                QualityCheckService.PACKAGE_CONTENTS,
                QualityCheckStatus.PASSED,
                "current-evidence",
                "Package mode `thin` has 0 dependency dispositions.",
                "");
    }

    @Test
    void packageContentsReportsUnreadableEvidenceManifest() throws IOException {
        Path projectDir = tempDir.resolve("bad-evidence");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, "");
        Path jar = projectDir.resolve("target/bad-evidence-0.1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar bytes\n");
        Files.writeString(projectDir.resolve("target/bad-evidence-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1"
                }
                """);

        QualityCheckResult result = check.checkContents(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertEquals(QualityCheckService.PACKAGE_CONTENTS, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("target/bad-evidence-0.1.0.jar.zolt-package.json", result.subject());
        assertTrue(result.message().contains("is missing string field `archive`"));
        assertEquals("Run `zolt package` to regenerate package evidence.", result.nextStep());
    }

    private static String sha256(Path path) throws IOException {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required for package content tests.", exception);
        }
    }
}
