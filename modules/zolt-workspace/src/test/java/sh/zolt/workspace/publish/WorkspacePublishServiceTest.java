package sh.zolt.workspace.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.BuildResult;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.publish.CentralPortalClient;
import sh.zolt.publish.PublishException;
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
    void workspacePublishDryRunAndUploadPreserveMemberRepositoryCredentials(@TempDir Path tempDir)
            throws IOException {
        String token = System.getenv("PATH");
        assumeTrue(token != null && !token.isBlank(), "PATH is required as a non-secret test token");
        PublishFixtureRepository repository = PublishFixtureRepository.start();
        try {
            writeWorkspace(tempDir, "lib");
            Files.writeString(
                    tempDir.resolve("zolt-workspace.toml"),
                    """
                    [workspace]
                    name = "authenticated-publish"
                    members = ["lib"]

                    [repositories]
                    internal = "%s"
                    """.formatted(repository.baseUri()));
            Path member = tempDir.resolve("lib");
            writeMember(member, """
                    [project]
                    name = "lib"
                    version = "1.0.0"
                    group = "com.acme"
                    java = "21"

                    [repositories]
                    internal = { url = "%s", credentials = "company" }

                    [repositoryCredentials.company]
                    tokenEnv = "PATH"

                    [publish]
                    releaseRepository = "internal"

                    [publish.repositories.internal]
                    url = "%s"
                    credentials = "company"
                    """.formatted(repository.baseUri(), repository.baseUri()));
            Path artifact = member.resolve("target/lib-1.0.0.jar");
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "authenticated workspace publish\n");
            writeEvidence(member, new PackagePlanService());

            WorkspacePublishService service = new WorkspacePublishService();
            WorkspacePublishReport dryRun = service.publish(
                    tempDir,
                    tempDir.resolve("cache"),
                    new WorkspaceSelectionRequest(true, List.of()),
                    new WorkspacePublishService.Options(true, false, false, false, Optional.empty()));
            assertTrue(dryRun.ok(), () -> "blockers: " + dryRun.blockers());
            assertFalse(dryRun.uploaded());

            WorkspacePublishReport published = service.publish(
                    tempDir,
                    tempDir.resolve("cache"),
                    new WorkspaceSelectionRequest(true, List.of()),
                    new WorkspacePublishService.Options(false, false, false, false, Optional.empty()));
            assertTrue(published.ok(), () -> "blockers: " + published.blockers());
            assertTrue(published.uploaded());
            assertFalse(repository.authByPath.isEmpty());
            assertTrue(repository.authByPath.values().stream()
                    .allMatch(("Bearer " + token)::equals));
        } finally {
            repository.close();
        }
    }

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
        writeEvidence(member, new PackagePlanService());

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
    void frameworkFastJarMemberRejectsSingleArtifactPublication(@TempDir Path tempDir)
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
        PackagePlanService fastJarPlanService =
                new PackagePlanService(List.of(new FastJarRules()));
        writeEvidence(member, fastJarPlanService);

        // The composition root injects the framework package-plan rules; here a fast-jar stub stands in
        // for QuarkusPackagePlanRules (which zolt-workspace does not depend on), proving the injection
        // resolves ANY framework mode's real archive rather than re-deriving package logic in publish.
        WorkspacePublishService service = new WorkspacePublishService(
                new MavenRepositoryClient(),
                new CentralPortalClient(),
                fastJarPlanService);
        PublishException exception = assertThrows(
                PublishException.class,
                () -> service.publish(
                        tempDir,
                        tempDir.resolve("cache"),
                        new WorkspaceSelectionRequest(true, List.of()),
                        new WorkspacePublishService.Options(
                                false,
                                false,
                                false,
                                false,
                                Optional.empty())));

        assertTrue(exception.getMessage().contains("multi-file runtime layout"));
        assertFalse(
                Files.exists(repository.resolve("com/acme/svc/1.0.0/svc-1.0.0.jar")),
                "the runner alone is never uploaded as the member artifact");
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

    private static void writeEvidence(
            Path member,
            PackagePlanService packagePlanService) throws IOException {
        ProjectConfig config =
                new ZoltTomlParser().parse(member.resolve("zolt.toml"));
        PackagePlan plan = packagePlanService.plan(
                member,
                config,
                new ZoltLockfile(
                        ZoltLockfile.CURRENT_VERSION,
                        List.of(),
                        List.of()));
        if (plan.runtimeClasspathPath().isPresent()) {
            Files.writeString(
                    plan.runtimeClasspathPath().orElseThrow(),
                    "");
        }
        PackageResult result = new PackageResult(
                new BuildResult(
                        Optional.empty(),
                        0,
                        0,
                        member.resolve("target/classes"),
                        ""),
                plan.mode(),
                plan.archivePath(),
                plan.runtimeClasspathPath(),
                Optional.empty(),
                1,
                config.project().main().isPresent(),
                plan.applicationLayout(),
                List.of(),
                List.of());
        new PackageEvidenceManifestWriter().write(
                member,
                config,
                plan,
                result,
                List.of());
    }
}
