package sh.zolt.quality.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.publish.PublishDryRunService;
import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckService;
import sh.zolt.quality.QualityCheckStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishDryRunQualityCheckTest {
    private final PublishDryRunQualityCheck check = new PublishDryRunQualityCheck(new PublishDryRunService());

    @TempDir
    private Path tempDir;

    @Test
    void skipsWhenNotCiOrDryRunIsNotRequired() {
        assertEquals(List.of(), check.check(Optional.empty(), tempDir, QualityCheckContext.LOCAL, true));
        assertEquals(List.of(), check.check(Optional.empty(), tempDir, QualityCheckContext.CI, false));
    }

    @Test
    void workspaceMemberDryRunDefersToTheOneShotFamilyPreflight() {
        // The per-member path no longer hard-fails; the family preflight (checkWorkspaceFamily) is the gate.
        assertEquals(
                List.of(),
                check.check(Optional.of("modules/api"), tempDir, QualityCheckContext.CI, true));
    }

    @Test
    void mapsPublishPlannerExceptionsToActionableCheckFailure() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [project]
                name = "publish-dry-run"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        QualityCheckResult result = check.check(Optional.empty(), tempDir, QualityCheckContext.CI, true).getFirst();

        assertResult(
                result,
                "publish-dry-run",
                "CI publish dry-run preflight failed: No [publish] configuration found. Add release/snapshot publish repositories before running `zolt publish --dry-run`.",
                "Configure [publish], run `zolt package`, then retry `zolt check --context ci --require-publish-dry-run`.");
    }

    @Test
    void mapsEachDryRunBlockerToSeparateFailedResult() throws IOException {
        Path projectDir = tempDir.resolve("publish-blockers");
        writePublishProject(projectDir, "publish-blockers", "https://user:secret@repo.example.test/releases");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");

        List<QualityCheckResult> results = check.check(
                Optional.empty(),
                projectDir,
                QualityCheckContext.CI,
                true);

        assertEquals(2, results.size());
        assertResult(
                results.get(0),
                "publish-dry-run",
                "CI publish dry-run blocker: publish repository `company-releases` URL contains embedded credentials. Move credentials to [repositoryCredentials] environment references.",
                "Run `zolt publish --dry-run` and resolve the reported blocker before release CI.");
        assertResult(
                results.get(1),
                "publish-dry-run",
                "CI publish dry-run blocker: missing artifact: run `zolt package` to create target/publish-blockers-1.0.0.jar",
                "Run `zolt publish --dry-run` and resolve the reported blocker before release CI.");
    }

    @Test
    void passesWithArtifactEvidenceAndReportsSupplementalArtifactCount() throws IOException {
        Path projectDir = tempDir.resolve("publish-ready");
        writePublishProject(projectDir, "publish-ready", "https://repo.example.test/releases");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        + "\n[package]\nsources = true\n");
        writePackageEvidence(projectDir, "publish-ready");

        QualityCheckResult result = check.check(
                Optional.empty(),
                projectDir,
                QualityCheckContext.CI,
                true).getFirst();

        assertEquals(QualityCheckService.EXECUTION_CONTEXT, result.id());
        assertEquals(QualityCheckStatus.PASSED, result.status());
        assertEquals(Optional.empty(), result.member());
        assertEquals("publish-dry-run", result.subject());
        assertEquals(
                "CI publish dry-run preflight is ready for com.example:publish-ready:1.0.0 to repository `company-releases` with 2 artifacts and generated POM metadata.",
                result.message());
        assertEquals("", result.nextStep());
    }

    private static void writePublishProject(Path projectDir, String name, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "%s"
                """.formatted(name, repositoryUrl));
    }

    private static void writePackageEvidence(Path projectDir, String name) throws IOException {
        Path target = projectDir.resolve("target");
        Files.createDirectories(target);
        Path archive = target.resolve(name + "-1.0.0.jar");
        Path sources = target.resolve(name + "-1.0.0-sources.jar");
        Files.writeString(archive, "archive\n", StandardCharsets.UTF_8);
        Files.writeString(sources, "sources\n", StandardCharsets.UTF_8);
        ProjectConfig config = new ZoltTomlParser().parse(
                projectDir.resolve("zolt.toml"));
        PackagePlan plan = new PackagePlanService().plan(
                projectDir,
                config,
                new ZoltLockfileReader().read(
                        projectDir.resolve("zolt.lock")));
        Files.writeString(
                plan.runtimeClasspathPath().orElseThrow(),
                "");
        PackageArtifact sourcesArtifact =
                new PackageArtifact("sources", sources, 1);
        PackageResult result = new PackageResult(
                new BuildResult(
                        Optional.empty(),
                        0,
                        0,
                        target.resolve("classes"),
                        ""),
                plan.mode(),
                archive,
                plan.runtimeClasspathPath(),
                Optional.empty(),
                1,
                false,
                plan.applicationLayout(),
                List.of(sourcesArtifact),
                List.of());
        new PackageEvidenceManifestWriter().write(
                projectDir,
                config,
                plan,
                result,
                List.of(sourcesArtifact));
    }

    private static void assertResult(
            QualityCheckResult result,
            String subject,
            String message,
            String nextStep) {
        assertEquals(QualityCheckService.EXECUTION_CONTEXT, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals(subject, result.subject());
        assertEquals(message, result.message());
        assertEquals(nextStep, result.nextStep());
    }
}
