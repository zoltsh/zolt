package sh.zolt.cli.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

final class CheckWorkspacePackageContentsCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void ciUsesDisjointMemberPackagePlansAndHandlesBomMembers()
            throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifact(repository, "org.example", "web-runtime");
            addArtifact(
                    repository,
                    "org.apache.tomcat.embed",
                    "tomcat-embed-core");
            addArtifact(repository, "org.example", "optional-lib");
            Path workspace = writeWorkspace(repository);
            Path cache = tempDir.resolve("package-content-cache");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());

            CommandResult check = execute(
                    "check",
                    "--workspace",
                    "--context", "ci",
                    "--all",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());

            assertEquals(0, check.exitCode(), () -> check.stdout() + check.stderr());
            assertFalse(
                    check.stdout().contains("error package-contents"),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok package-contents apps/webapp webapp Package mode `war` has 1 dependency dispositions."),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok package-contents tools/admin admin Package mode `thin` has 1 dependency dispositions."),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok package-contents platform platform Package mode `bom` has 0 dependency dispositions."),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok package-contents apps/consumer consumer Package mode `uber` has 1 dependency dispositions."),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok package-contents apps/required-consumer required-consumer Package mode `uber` has 2 dependency dispositions."),
                    check.stdout());
            assertFalse(
                    check.stdout().contains(
                            "package-contents apps/webapp org.apache.tomcat.embed:tomcat-embed-core"),
                    check.stdout());
            assertEquals("", check.stderr());

            CommandResult packageResult = execute(
                    "package",
                    "--workspace",
                    "--all",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(
                    0,
                    packageResult.exitCode(),
                    () -> packageResult.stdout() + packageResult.stderr());

            String webEvidence = evidence(
                    workspace,
                    "apps/webapp",
                    "webapp-0.1.0.war");
            String adminEvidence = evidence(
                    workspace,
                    "tools/admin",
                    "admin-0.1.0.jar");
            String consumerEvidence = evidence(
                    workspace,
                    "apps/consumer",
                    "consumer-0.1.0.jar");
            String requiredConsumerEvidence = evidence(
                    workspace,
                    "apps/required-consumer",
                    "required-consumer-0.1.0.jar");
            assertTrue(webEvidence.contains("org.example:web-runtime"));
            assertFalse(webEvidence.contains("tomcat-embed-core"));
            assertTrue(adminEvidence.contains("tomcat-embed-core"));
            assertFalse(adminEvidence.contains("org.example:web-runtime"));
            assertTrue(consumerEvidence.contains("com.example:provider"));
            assertFalse(consumerEvidence.contains("org.example:optional-lib"));
            assertTrue(requiredConsumerEvidence.contains("com.example:provider"));
            assertTrue(requiredConsumerEvidence.contains("org.example:optional-lib"));
            Path bomPom = workspace.resolve(
                    "platform/target/publish/platform-0.1.0.pom");
            Path bomEvidence = workspace.resolve(
                    "platform/target/publish/platform-0.1.0.pom.zolt-package.json");
            assertTrue(Files.isRegularFile(bomPom));
            assertTrue(Files.isRegularFile(bomEvidence));

            CommandResult requiredPackage = packageContentsCheck(
                    workspace,
                    cache);
            assertEquals(
                    0,
                    requiredPackage.exitCode(),
                    () -> requiredPackage.stdout() + requiredPackage.stderr());

            Files.writeString(
                    bomPom,
                    "\n<!-- stale -->\n",
                    StandardOpenOption.APPEND);
            CommandResult staleBom = packageContentsCheck(workspace, cache);
            assertEquals(1, staleBom.exitCode());
            assertTrue(staleBom.stdout().contains(
                    "Package evidence manifest is stale for "
                            + "`target/publish/platform-0.1.0.pom`"));

            CommandResult restoredPackage = execute(
                    "package",
                    "--workspace",
                    "--member", "platform",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(
                    0,
                    restoredPackage.exitCode(),
                    () -> restoredPackage.stdout()
                            + restoredPackage.stderr());
            Files.delete(bomEvidence);

            CommandResult missingBomEvidence = packageContentsCheck(
                    workspace,
                    cache);
            assertEquals(1, missingBomEvidence.exitCode());
            assertTrue(missingBomEvidence.stdout().contains(
                    "Package artifact exists, but package evidence manifest is missing."));
        }
    }

    private static CommandResult packageContentsCheck(
            Path workspace,
            Path cache) {
        return execute(
                "check",
                "--workspace",
                "--context", "ci",
                "--check", "package-contents",
                "--require-package",
                "--all",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static String evidence(
            Path workspace,
            String member,
            String artifact) throws IOException {
        return Files.readString(workspace.resolve(member)
                .resolve("target")
                .resolve(artifact + ".zolt-package.json"));
    }

    private Path writeWorkspace(CliTestRepository repository)
            throws IOException {
        Path workspace = tempDir.resolve("package-content-workspace");
        writeMember(
                workspace,
                "apps/webapp",
                memberConfig("webapp")
                        + """

                        [package]
                        mode = "war"

                        [runtime.dependencies]
                        "org.example:web-runtime" = "1.0.0"
                        """);
        writeMember(
                workspace,
                "tools/admin",
                memberConfig("admin")
                        + """

                        [runtime.dependencies]
                        "org.apache.tomcat.embed:tomcat-embed-core" = "1.0.0"
                        """);
        writeMember(
                workspace,
                "platform",
                memberConfig("platform")
                        + """

                        [bom]
                        """);
        writeMember(
                workspace,
                "modules/provider",
                memberConfig("provider")
                        + """

                        [api.dependencies]
                        "org.example:optional-lib" = { version = "1.0.0", optional = true }
                        """);
        writeMember(
                workspace,
                "apps/consumer",
                memberConfig("consumer")
                        + """

                        [package]
                        mode = "uber"

                        [dependencies]
                        "com.example:provider" = { workspace = "modules/provider" }
                        """);
        writeMember(
                workspace,
                "apps/required-consumer",
                memberConfig("required-consumer")
                        + """

                        [package]
                        mode = "uber"

                        [dependencies]
                        "com.example:provider" = { workspace = "modules/provider" }
                        "org.example:optional-lib" = "1.0.0"
                        """);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "package-content-workspace"
                members = [
                  "apps/webapp",
                  "tools/admin",
                  "platform",
                  "modules/provider",
                  "apps/consumer",
                  "apps/required-consumer"
                ]

                [repositories]
                test = "%s"
                """.formatted(repository.baseUri()));
        return workspace;
    }

    private static void writeMember(
            Path workspace,
            String member,
            String config) throws IOException {
        Path directory = workspace.resolve(member);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), config);
    }

    private static void addArtifact(
            CliTestRepository repository,
            String group,
            String artifact) {
        repository.addArtifact(
                group,
                artifact,
                "1.0.0",
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>MIT License</name></license></licenses>
                </project>
                """.formatted(group, artifact));
    }
}
