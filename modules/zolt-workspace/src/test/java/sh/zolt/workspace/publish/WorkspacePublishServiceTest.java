package sh.zolt.workspace.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.CentralPortalClient;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Family-publish behaviours that live in {@link WorkspacePublishService}'s Phase-1 planning. */
final class WorkspacePublishServiceTest {

    @Test
    void warMemberPlansAndUploadsItsWarArchiveRatherThanAJar(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("repo");
        Files.createDirectories(repository);
        String repositoryUrl = repository.toUri().toString();
        writeWorkspace(tempDir, "web-app");
        // A WAR member: its real archive is a .war, not a .jar.
        Path member = tempDir.resolve("web-app");
        writeMember(member, """
                [project]
                name = "web-app"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [package]
                mode = "war"

                [publish]
                releaseRepository = "local"

                [publish.repositories.local]
                url = "%s"
                """.formatted(repositoryUrl));
        Path war = member.resolve("target/web-app-1.0.0.war");
        Files.createDirectories(war.getParent());
        Files.writeString(war, "fake war archive\n");
        Files.writeString(member.resolve("target/web-app-1.0.0.war.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/web-app-1.0.0.war",
                  "archiveSha256": "sha256:%s"
                }
                """.formatted(Sha256.hex(war)));

        WorkspacePublishReport report = new WorkspacePublishService().publish(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(true, List.of()),
                new WorkspacePublishService.Options(false, false, false, false, Optional.empty()));

        assertTrue(report.ok(), () -> "blockers: " + report.blockers());
        assertTrue(report.uploaded());
        Path uploaded = repository.resolve("com/acme/web-app/1.0.0/web-app-1.0.0.war");
        assertTrue(Files.exists(uploaded), "the .war archive was uploaded");
        assertFalse(
                Files.exists(repository.resolve("com/acme/web-app/1.0.0/web-app-1.0.0.jar")),
                "no phantom .jar is published for a WAR member");
    }

    @Test
    void frameworkFastJarMemberPublishesItsRealRunnerArtifactNotASynthesizedJar(@TempDir Path tempDir)
            throws IOException {
        Path repository = tempDir.resolve("repo");
        Files.createDirectories(repository);
        String repositoryUrl = repository.toUri().toString();
        writeWorkspace(tempDir, "svc");
        // A framework fast-jar member (Quarkus): its real archive is target/quarkus-app/quarkus-run.jar,
        // nothing like the synthesized target/svc-1.0.0.jar the old planner assumed.
        Path member = tempDir.resolve("svc");
        writeMember(member, """
                [project]
                name = "svc"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [package]
                mode = "quarkus"

                [publish]
                releaseRepository = "local"

                [publish.repositories.local]
                url = "%s"
                """.formatted(repositoryUrl));
        Path runnerJar = member.resolve("target/quarkus-app/quarkus-run.jar");
        Files.createDirectories(runnerJar.getParent());
        Files.writeString(runnerJar, "fake quarkus runner jar\n");
        Files.writeString(member.resolve("target/quarkus-app/quarkus-run.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/quarkus-app/quarkus-run.jar",
                  "archiveSha256": "sha256:%s"
                }
                """.formatted(Sha256.hex(runnerJar)));

        // The composition root injects the framework package-plan rules; here a fast-jar stub stands in
        // for QuarkusPackagePlanRules (which zolt-workspace does not depend on), proving the injection
        // resolves ANY framework mode's real archive rather than re-deriving package logic in publish.
        WorkspacePublishService service = new WorkspacePublishService(
                new MavenRepositoryClient(),
                new CentralPortalClient(),
                new PackagePlanService(List.of(new FastJarRules())));
        WorkspacePublishReport report = service.publish(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(true, List.of()),
                new WorkspacePublishService.Options(false, false, false, false, Optional.empty()));

        assertTrue(report.ok(), () -> "blockers: " + report.blockers());
        assertTrue(report.uploaded());
        Path uploaded = repository.resolve("com/acme/svc/1.0.0/svc-1.0.0.jar");
        assertTrue(Files.exists(uploaded), "the real runner jar was uploaded at the member's canonical coordinate");
        assertEquals("fake quarkus runner jar\n", Files.readString(uploaded), "uploaded bytes are the runner jar's");
        assertFalse(
                Files.exists(repository.resolve("com/acme/svc/1.0.0/svc-1.0.0-runner.jar")),
                "no phantom runner-classifier artifact is published");
    }

    /** A stand-in for a framework's fast-jar package rules: its real archive is a runner jar. */
    private static final class FastJarRules implements FrameworkPackagePlanRules {
        @Override
        public boolean supports(PackageMode mode) {
            return mode == PackageMode.QUARKUS;
        }

        @Override
        public FrameworkPackagePlanDependency dependency(LockPackage lockPackage, ProjectConfig config) {
            throw new UnsupportedOperationException("no lock packages in this fixture");
        }

        @Override
        public Path archivePath(Path projectRoot, ProjectConfig config) {
            return projectRoot.resolve("target/quarkus-app/quarkus-run.jar");
        }

        @Override
        public String applicationLayout(ProjectConfig config) {
            return "target/quarkus-app/app";
        }
    }

    @Test
    void divergentCentralOrSigningSettingsAcrossMembersBlockTheFamily(@TempDir Path tempDir) throws IOException {
        writeWorkspace(tempDir, "lib-a", "lib-b");
        writeMember(tempDir.resolve("lib-a"), centralMemberToml("lib-a", "ENV_A", "AAAA1111"));
        writeMember(tempDir.resolve("lib-b"), centralMemberToml("lib-b", "ENV_B", "BBBB2222"));

        WorkspacePublishReport report = new WorkspacePublishService().publish(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(true, List.of()),
                new WorkspacePublishService.Options(true, true, false, false, Optional.empty()));

        assertFalse(report.ok());
        String divergence = report.blockers().stream()
                .filter(blocker -> blocker.contains("diverge"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no divergence blocker in " + report.blockers()));
        assertTrue(divergence.contains("lib-a"), divergence);
        assertTrue(divergence.contains("lib-b"), divergence);
        assertTrue(divergence.contains("signing key"), divergence);
        assertTrue(divergence.contains("token env"), divergence);
        assertTrue(divergence.contains("[publish.central]/[publish.signing]"), divergence);
    }

    private static String centralMemberToml(String name, String tokenEnv, String keyId) {
        return """
                [project]
                name = "%s"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [publish]
                releaseRepository = "local"

                [publish.repositories.local]
                url = "https://repo.example.test/releases"

                [publish.central]
                tokenEnv = "%s"

                [publish.signing]
                enabled = true
                keyId = "%s"
                """.formatted(name, tokenEnv, keyId);
    }

    private static void writeWorkspace(Path root, String... members) throws IOException {
        StringBuilder toml = new StringBuilder("[workspace]\nname = \"acme-platform\"\nmembers = [");
        for (int index = 0; index < members.length; index++) {
            toml.append(index == 0 ? "" : ", ").append('"').append(members[index]).append('"');
        }
        toml.append("]\n");
        Files.writeString(root.resolve("zolt-workspace.toml"), toml.toString());
        Files.writeString(root.resolve("zolt.lock"), "version = 5\n");
    }

    private static void writeMember(Path member, String toml) throws IOException {
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), toml);
    }
}
