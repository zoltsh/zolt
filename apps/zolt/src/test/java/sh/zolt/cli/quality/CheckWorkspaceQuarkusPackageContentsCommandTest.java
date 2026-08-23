package sh.zolt.cli.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.lockfile.LockPackageCachePath;
import sh.zolt.lockfile.LockPackagePathKind;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.quarkus.QuarkusPackagePlanRules;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.packaging.WorkspacePackageResult;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;

final class CheckWorkspaceQuarkusPackageContentsCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void packagedQuarkusMemberUsesRunnerAndFrameworkRulesInQuality()
            throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact(
                    "org.example",
                    "runtime",
                    "1.0.0",
                    """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>org.example</groupId>
                      <artifactId>runtime</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path workspace = writeWorkspace(repository);
            Path cache = tempDir.resolve("quarkus-cache");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());

            CommandResult beforePackage = packageContentsCheck(
                    workspace,
                    cache,
                    false);
            assertEquals(
                    0,
                    beforePackage.exitCode(),
                    () -> beforePackage.stdout()
                            + beforePackage.stderr());
            assertTrue(beforePackage.stdout().contains(
                    "rule:quarkus-runtime-lib"));
            assertFalse(beforePackage.stdout().contains(
                    "framework-package-plan-rules-missing"));

            PackagePlanService packagePlanService =
                    new PackagePlanService(
                            List.of(new QuarkusPackagePlanRules()));
            WorkspacePackageResult packaged =
                    new WorkspacePackageService(
                            new ResolveService(),
                            fakeQuarkusAugmenter(),
                            packagePlanService)
                            .packageJars(
                                    workspace,
                                    cache,
                                    new WorkspaceSelectionRequest(
                                            false,
                                            List.of("apps/service")));

            assertEquals(1, packaged.members().size());
            Path runner = workspace.resolve(
                    "apps/service/target/quarkus-app/quarkus-run.jar");
            assertTrue(Files.isRegularFile(runner));
            assertFalse(Files.exists(workspace.resolve(
                    "apps/service/target/service-0.1.0.jar")));
            String evidence = Files.readString(runner.resolveSibling(
                    "quarkus-run.jar.zolt-package.json"));
            assertTrue(evidence.contains(
                    "\"rule\": \"quarkus-runtime-lib\""));

            CommandResult requiredPackage = packageContentsCheck(
                    workspace,
                    cache,
                    true);
            assertEquals(
                    0,
                    requiredPackage.exitCode(),
                    () -> requiredPackage.stdout()
                            + requiredPackage.stderr());
            assertTrue(requiredPackage.stdout().contains(
                    "ok package-contents apps/service service "
                            + "Package mode `quarkus`"));
            assertTrue(requiredPackage.stdout().contains(
                    "rule:quarkus-runtime-lib"));
            assertFalse(requiredPackage.stdout().contains(
                    "target/service-0.1.0.jar"));
        }
    }

    private Path writeWorkspace(CliTestRepository repository)
            throws IOException {
        Path workspace = tempDir.resolve("quarkus-workspace");
        Path member = workspace.resolve("apps/service");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), memberConfig("service")
                + """

                [dependencies]
                "org.example:runtime" = "1.0.0"

                [package]
                mode = "quarkus"
                """);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "quarkus-workspace"

                [workspace.members]
                include = ["apps/service"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(repository.baseUri()));
        return workspace;
    }

    private static FrameworkPackageAugmenter fakeQuarkusAugmenter() {
        return (context, config, cacheRoot) -> {
            Path packageDirectory =
                    context.projectRoot().resolve("target/quarkus-app");
            Path runner =
                    packageDirectory.resolve("quarkus-run.jar");
            Path libraryDirectory = packageDirectory.resolve("lib");
            try {
                Files.createDirectories(libraryDirectory);
                try (JarOutputStream ignored =
                        new JarOutputStream(Files.newOutputStream(runner))) {
                    // A deterministic empty runner is enough for the package contract seam.
                }
                // The seam names the authoritative lock, so the adapter reads it instead of climbing
                // out of the member directory to guess where the workspace root is (design §4.5).
                Path runtimeJar = new ZoltLockfileReader()
                        .read(context.lockfilePath())
                        .packages()
                        .stream()
                        .filter(lockPackage -> lockPackage.packageId().groupId().equals("org.example"))
                        .filter(lockPackage -> lockPackage.packageId().artifactId().equals("runtime"))
                        .findFirst()
                        .flatMap(lockPackage -> LockPackageCachePath.path(
                                lockPackage, LockPackagePathKind.JAR))
                        .orElseThrow()
                        .resolveWithin(cacheRoot);
                Files.copy(
                        runtimeJar,
                        libraryDirectory.resolve("runtime-1.0.0.jar"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new PackageException(
                        "Could not create Quarkus package fixture.",
                        exception);
            }
            return Optional.of(new FrameworkPackageResult(
                    PackageMode.QUARKUS,
                    packageDirectory,
                    runner,
                    "target/quarkus-app/app"));
        };
    }

    private static CommandResult packageContentsCheck(
            Path workspace,
            Path cache,
            boolean requirePackage) {
        java.util.ArrayList<String> arguments =
                new java.util.ArrayList<>(List.of(
                        "check",
                        "--workspace",
                        "--context", "ci",
                        "--check", "package-contents",
                        "--cwd", workspace.toString(),
                        "--cache-root", cache.toString()));
        if (requirePackage) {
            arguments.add("--require-package");
        }
        return execute(arguments.toArray(String[]::new));
    }
}
