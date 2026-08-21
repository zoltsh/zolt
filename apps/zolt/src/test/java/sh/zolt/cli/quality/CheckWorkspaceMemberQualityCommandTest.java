package sh.zolt.cli.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * End-to-end guard for the version-5 member-qualified model used by {@code check --workspace}.
 */
final class CheckWorkspaceMemberQualityCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void ciContextUsesMemberQualifiedMetadataPolicyAndLicenseViews() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addRepositoryArtifacts(repository);
            Path workspace = writeWorkspace(repository);
            Path cache = tempDir.resolve("cache");

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
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());

            assertEquals(0, check.exitCode(), () -> check.stdout() + check.stderr());
            assertFalse(check.stdout().contains("error "), check.stdout());
            assertTrue(check.stdout().contains(
                    "ok dependency-metadata modules/core com.example:feature-api Optional workspace API dependency"));
            assertTrue(check.stdout().contains(
                    "ok dependency-metadata apps/admin org.example:dual Dependency metadata for `org.example:dual` is represented in zolt.lock for variant `jar|linux` and scope `runtime`."));
            assertTrue(check.stdout().contains(
                    "ok dependency-metadata modules/core org.example:parent Dependency metadata for `org.example:parent` is represented in zolt.lock for variant `jar` and scope `compile`."));
            assertTrue(check.stdout().contains(
                    "ok dependency-policy modules/core core Dependency policy baseline is explainable: 1 platform"));
            assertTrue(check.stdout().contains(
                    "ok license-policy modules/core [dependencyPolicy.licenses] Evaluated 2 compile/runtime dependencies against [dependencyPolicy.licenses]: 0 violation(s), 0 warning(s)."));
            assertTrue(check.stdout().contains(
                    "ok license-policy apps/admin [dependencyPolicy.licenses] Evaluated 2 compile/runtime dependencies against [dependencyPolicy.licenses]: 0 violation(s), 0 warning(s)."));
            assertFalse(check.stdout().contains("license-policy modules/core org.example:admin-only"), check.stdout());
            assertEquals("", check.stderr());
        }
    }

    @Test
    void explicitGraphDependentChecksRejectPreVersionFiveWorkspaceLock() throws IOException {
        Path workspace = tempDir.resolve("legacy-quality-workspace");
        Path app = workspace.resolve("app");
        Files.createDirectories(app);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "legacy-quality-workspace"
                members = ["app"]
                """);
        Files.writeString(app.resolve("zolt.toml"), memberConfig("app") + """

                [dependencyPolicy.licenses]
                unknown = "fail"
                """);
        Files.writeString(workspace.resolve("zolt.lock"), "version = 4\n");

        for (String qualityCheck : new String[] {
                "dependency-metadata",
                "dependency-policy",
                "license-policy",
                "package-contents"
        }) {
            CommandResult result = execute(
                    "check",
                    "--workspace",
                    "--check", qualityCheck,
                    "--cwd", workspace.toString());

            assertEquals(1, result.exitCode(), qualityCheck);
            assertTrue(result.stdout().contains("error " + qualityCheck + " zolt.lock"), result.stdout());
            assertTrue(result.stdout().contains("version 4"), result.stdout());
            assertTrue(
                    result.stdout().contains("version 4 is older than this Zolt supports (current 7)"),
                    result.stdout());
            assertTrue(result.stdout().contains("zolt resolve --workspace"), result.stdout());
            assertEquals("", result.stderr());
        }
    }

    @Test
    void directlyOptionalPackageWithRequiredReachabilityPassesWorkspaceMetadataCheck() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact(
                    "org.example",
                    "feature",
                    "1.0.0",
                    pom("feature", "MIT License", ""));
            repository.addArtifact(
                    "org.example",
                    "required-root",
                    "1.0.0",
                    pom(
                            "required-root",
                            "MIT License",
                            """
                              <dependencies>
                                <dependency>
                                  <groupId>org.example</groupId>
                                  <artifactId>feature</artifactId>
                                  <version>1.0.0</version>
                                </dependency>
                              </dependencies>
                            """));
            Path workspace = tempDir.resolve("mixed-optionality-workspace");
            Path core = workspace.resolve("modules/core");
            Files.createDirectories(core);
            Files.writeString(workspace.resolve("zolt.toml"), """
                    [workspace]
                    name = "mixed-optionality"
                    members = ["modules/core"]

                    [repositories]
                    test = "%s"
                    """.formatted(repository.baseUri()));
            Files.writeString(core.resolve("zolt.toml"), memberConfig("core") + """

                    [api.dependencies]
                    "org.example:feature" = { version = "1.0.0", optional = true }
                    "org.example:required-root" = "1.0.0"
                    """);
            Path cache = tempDir.resolve("mixed-optionality-cache");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            CommandResult check = execute(
                    "check",
                    "--workspace",
                    "--check", "dependency-metadata",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());

            assertEquals(0, check.exitCode(), () -> check.stdout() + check.stderr());
            assertTrue(check.stdout().contains(
                    "ok dependency-metadata modules/core org.example:feature"), check.stdout());
            assertFalse(check.stdout().contains("error dependency-metadata"), check.stdout());
        }
    }

    @Test
    void memberLicensePolicyFiltersExactExternalVariantsFromCollidingWorkspaceCoordinate()
            throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact(
                    "com.acme",
                    "core",
                    "1.0.0",
                    """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.acme</groupId>
                      <artifactId>core</artifactId>
                      <version>1.0.0</version>
                      <licenses><license><name>MIT License</name></license></licenses>
                    </project>
                    """);
            repository.addClassifiedArtifact("com.acme", "core", "1.0.0", "tests", "jar");
            repository.addTypedArtifact("com.acme", "core", "1.0.0", "zip");
            Path workspace = tempDir.resolve("license-identity-workspace");
            Path core = workspace.resolve("modules/core");
            Path bridge = workspace.resolve("modules/bridge");
            Path app = workspace.resolve("apps/app");
            Path zipApp = workspace.resolve("apps/zip-app");
            Files.createDirectories(core);
            Files.createDirectories(bridge);
            Files.createDirectories(app);
            Files.createDirectories(zipApp);
            Files.writeString(workspace.resolve("zolt.toml"), """
                    [workspace]
                    name = "license-identity"
                    members = ["modules/core", "modules/bridge", "apps/app", "apps/zip-app"]

                    [repositories]
                    test = "%s"
                    """.formatted(repository.baseUri()));
            Files.writeString(core.resolve("zolt.toml"), """
                    [project]
                    name = "core"
                    version = "1.0.0"
                    group = "com.acme"
                    java = "21"
                    """);
            Files.writeString(bridge.resolve("zolt.toml"), """
                    [project]
                    name = "bridge"
                    version = "1.0.0"
                    group = "com.acme"
                    java = "21"

                    [api.dependencies]
                    "com.acme:core" = { workspace = "modules/core" }
                    """);
            Files.writeString(app.resolve("zolt.toml"), memberConfig("app") + """

                    [api.dependencies]
                    "com.acme:bridge" = { workspace = "modules/bridge" }

                    [dependencies]
                    "com.acme:core" = { version = "1.0.0", classifier = "tests" }

                    [dependencyPolicy.licenses]
                    allow = ["MIT"]
                    unknown = "fail"
                    """);
            Files.writeString(zipApp.resolve("zolt.toml"), memberConfig("zip-app") + """

                    [api.dependencies]
                    "com.acme:bridge" = { workspace = "modules/bridge" }

                    [runtime.dependencies]
                    "com.acme:core" = { version = "1.0.0", type = "zip" }

                    [dependencyPolicy.licenses]
                    allow = ["MIT"]
                    unknown = "fail"
                    """);
            Path cache = tempDir.resolve("license-identity-cache");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            CommandResult check = execute(
                    "check",
                    "--workspace",
                    "--check", "license-policy",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());

            assertEquals(0, check.exitCode(), () -> check.stdout() + check.stderr());
            assertTrue(check.stdout().contains(
                    "ok license-policy apps/app [dependencyPolicy.licenses] Evaluated 1 compile/runtime dependency"),
                    check.stdout());
            assertTrue(check.stdout().contains(
                    "ok license-policy apps/zip-app [dependencyPolicy.licenses] Evaluated 1 compile/runtime dependency"),
                    check.stdout());
            assertFalse(check.stdout().contains(
                    "Evaluated 2 compile/runtime dependencies"), check.stdout());
        }
    }

    private Path writeWorkspace(CliTestRepository repository) throws IOException {
        Path workspace = tempDir.resolve("member-quality-workspace");
        Path feature = workspace.resolve("modules/feature-api");
        Path core = workspace.resolve("modules/core");
        Path admin = workspace.resolve("apps/admin");
        Files.createDirectories(feature);
        Files.createDirectories(core);
        Files.createDirectories(admin);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "member-quality-workspace"
                members = ["modules/feature-api", "modules/core", "apps/admin"]

                [repositories]
                test = "%s"

                [platforms]
                "org.example:platform" = "1.0.0"
                """.formatted(repository.baseUri()));
        Files.writeString(feature.resolve("zolt.toml"), memberConfig("feature-api"));
        Files.writeString(core.resolve("zolt.toml"), memberConfig("core") + """

                [api.dependencies]
                "com.example:feature-api" = { workspace = "modules/feature-api", optional = true }

                [dependencies]
                "org.example:dual" = {}
                "org.example:parent" = { version = "1.0.0", exclusions = [{ group = "org.example", artifact = "excluded" }] }

                [dependencyPolicy.licenses]
                deny = ["Apache-2.0"]
                unknown = "fail"
                """);
        Files.writeString(admin.resolve("zolt.toml"), memberConfig("admin") + """

                [dependencies]
                "org.example:admin-only" = "1.0.0"

                [runtime.dependencies]
                "org.example:dual" = { classifier = "linux" }

                [dependencyPolicy.licenses]
                allow = ["Apache-2.0", "MIT"]
                unknown = "fail"
                """);
        return workspace;
    }

    private static void addRepositoryArtifacts(CliTestRepository repository) {
        repository.addArtifact(
                "org.example",
                "platform",
                "1.0.0",
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>dual</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        repository.addArtifact(
                "org.example",
                "dual",
                "1.0.0",
                pom("dual", "MIT License", ""));
        repository.addClassifiedArtifact(
                "org.example",
                "dual",
                "1.0.0",
                "linux",
                "jar");
        repository.addArtifact(
                "org.example",
                "parent",
                "1.0.0",
                pom(
                        "parent",
                        "MIT License",
                        """
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>excluded</artifactId>
                              <version>1.0.0</version>
                            </dependency>
                          </dependencies>
                        """));
        repository.addArtifact(
                "org.example",
                "admin-only",
                "1.0.0",
                pom("admin-only", "Apache License, Version 2.0", ""));
    }

    private static String pom(
            String artifact,
            String license,
            String extra) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>%s</name></license></licenses>
                %s
                </project>
                """.formatted(artifact, license, extra);
    }
}
