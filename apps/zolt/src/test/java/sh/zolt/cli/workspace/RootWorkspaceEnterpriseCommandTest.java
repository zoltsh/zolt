package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;

final class RootWorkspaceEnterpriseCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void rootMemberBuildCheckSbomAndPublishUseQualifiedV5Evidence()
            throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifacts(repository);
            Path workspace = writeWorkspace(repository);
            Path cache = tempDir.resolve("root-workspace-cache");

            CommandResult resolve = run(
                    workspace,
                    cache,
                    "resolve",
                    "--workspace");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            assertQualifiedLock(workspace);

            CommandResult build = run(
                    workspace,
                    cache,
                    "build",
                    "--workspace",
                    "--all");
            assertEquals(0, build.exitCode(), build.stderr());
            assertTrue(Files.isRegularFile(
                    workspace.resolve(
                            "target/classes/com/acme/app/App.class")));

            CommandResult check = run(
                    workspace,
                    cache,
                    "check",
                    "--workspace",
                    "--context",
                    "ci");
            assertEquals(
                    0,
                    check.exitCode(),
                    () -> check.stdout() + check.stderr());
            assertFalse(
                    check.stdout().contains("error "),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok dependency-metadata . org.example:optional"),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok license-policy . [dependencyPolicy.licenses] Evaluated 3 compile/runtime dependencies"),
                    check.stdout());

            Path sbomPath = workspace.resolve("target/root-workspace.cdx.json");
            CommandResult sbom = run(
                    workspace,
                    cache,
                    "sbom",
                    "--workspace",
                    "--output",
                    sbomPath.toString());
            assertEquals(0, sbom.exitCode(), sbom.stderr());
            String sbomJson = Files.readString(sbomPath);
            assertTrue(sbomJson.contains(
                    "\"ref\": \"pkg:maven/com.acme/app@1.0.0?type=jar\",\n"
                            + "      \"dependsOn\": [\"pkg:maven/org.example/api@1.0.0?type=jar\""),
                    sbomJson);
            assertTrue(sbomJson.contains(
                    "\"ref\": \"pkg:maven/org.example/api@1.0.0?type=jar\",\n"
                            + "      \"dependsOn\": [\"pkg:maven/org.example/leaf@1.0.0?type=jar\"]"),
                    sbomJson);

            CommandResult packageResult = run(
                    workspace,
                    cache,
                    "package",
                    "--workspace",
                    "--all");
            assertEquals(
                    0,
                    packageResult.exitCode(),
                    packageResult.stderr());

            CommandResult dryRun = run(
                    workspace,
                    cache,
                    "publish",
                    "--workspace",
                    "--dry-run");
            assertEquals(
                    0,
                    dryRun.exitCode(),
                    () -> dryRun.stdout() + dryRun.stderr());
            assertTrue(
                    dryRun.stdout().contains("Nothing uploaded (dry run)."),
                    dryRun.stdout());

            CommandResult publish = run(
                    workspace,
                    cache,
                    "publish",
                    "--workspace");
            assertEquals(
                    0,
                    publish.exitCode(),
                    () -> publish.stdout() + publish.stderr());
            assertTrue(
                    publish.stdout().contains("Uploaded the family."),
                    publish.stdout());
            assertTrue(
                    repository.uploaded(
                                    "/maven2/com/acme/app/1.0.0/app-1.0.0.jar")
                            .length
                            > 0);
            String publishedPom = new String(
                    repository.uploaded(
                            "/maven2/com/acme/app/1.0.0/app-1.0.0.pom"),
                    StandardCharsets.UTF_8);
            assertTrue(publishedPom.contains("<optional>true</optional>"));
        }
    }

    private void assertQualifiedLock(Path workspace) {
        ZoltLockfile lockfile =
                new ZoltLockfileReader().read(workspace.resolve("zolt.lock"));
        LockPackage api = packageById(lockfile, "api");
        LockPackage optional = packageById(lockfile, "optional");
        LockPackage leaf = packageById(lockfile, "leaf");

        assertEquals(7, lockfile.version());
        assertEquals(java.util.List.of("."), api.members());
        assertEquals(java.util.List.of("."), api.exportedBy());
        assertEquals(java.util.List.of("."), optional.members());
        assertEquals(java.util.List.of(), optional.exportedBy());
        assertEquals(java.util.List.of("."), leaf.members());
        assertTrue(lockfile.memberGraphs().stream()
                .anyMatch(graph -> graph.member().equals(".")
                        && graph.packageId().equals(optional.packageId())
                        && graph.declaredOptional()
                        && graph.optionalOnly()));
    }

    private static LockPackage packageById(
            ZoltLockfile lockfile,
            String artifactId) {
        PackageId id = new PackageId("org.example", artifactId);
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private CommandResult run(
            Path workspace,
            Path cache,
            String... command) {
        String[] args =
                java.util.Arrays.copyOf(command, command.length + 4);
        args[command.length] = "--cwd";
        args[command.length + 1] = workspace.toString();
        args[command.length + 2] = "--cache-root";
        args[command.length + 3] = cache.toString();
        return execute(args);
    }

    private Path writeWorkspace(CliTestRepository repository)
            throws IOException {
        Path workspace = tempDir.resolve("root-workspace");
        Files.createDirectories(
                workspace.resolve("src/main/java/com/acme/app"));
        Files.writeString(
                workspace.resolve("src/main/java/com/acme/app/App.java"),
                """
                package com.acme.app;

                public final class App {
                    private App() {
                    }
                }
                """);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [project]
                name = "app"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [workspace]
                name = "root-workspace"
                members = ["."]

                [repositories]
                test = "%s"

                [api.dependencies]
                "org.example:api" = "1.0.0"

                [dependencies]
                "org.example:optional" = { version = "1.0.0", optional = true }

                [dependencyPolicy.licenses]
                allow = ["MIT"]
                unknown = "fail"

                [publish]
                releaseRepository = "test"

                [publish.repositories.test]
                url = "%s"
                """.formatted(
                repository.baseUri(),
                repository.baseUri()));
        return workspace;
    }

    private static void addArtifacts(CliTestRepository repository) {
        repository.addArtifact(
                "org.example",
                "api",
                "1.0.0",
                pom(
                        "api",
                        """
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>leaf</artifactId>
                              <version>1.0.0</version>
                            </dependency>
                          </dependencies>
                        """));
        repository.addArtifact(
                "org.example",
                "optional",
                "1.0.0",
                pom("optional", ""));
        repository.addArtifact(
                "org.example",
                "leaf",
                "1.0.0",
                pom("leaf", ""));
    }

    private static String pom(String artifact, String extra) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>MIT License</name></license></licenses>
                %s
                </project>
                """.formatted(artifact, extra);
    }
}
