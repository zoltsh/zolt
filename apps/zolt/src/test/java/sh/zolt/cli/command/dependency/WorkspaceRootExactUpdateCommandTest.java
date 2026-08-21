package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.maven.metadata.VersionDiscovery;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.update.OutdatedSurface;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateTarget;
import sh.zolt.update.UpdateTargetCatalog;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class WorkspaceRootExactUpdateCommandTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();

    @Test
    void updatesModernRootPlatformFromMemberWithoutTouchingMemberManifest() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("modern"), "zolt.toml", "", "5.10.2");
        Path member = writeMember(root);
        String memberBefore = Files.readString(member.resolve("zolt.toml"));
        UpdateTarget target = rootTarget(root.resolve("zolt.toml"), "zolt.toml");

        Result result = run(member, null, exactArgs(target, "5.11.4", "--no-resolve"));

        assertEquals(0, result.exitCode(), result.stderr());
        String rootSource = Files.readString(root.resolve("zolt.toml"));
        assertTrue(rootSource.contains("\"org.junit:junit-bom\" = \"5.11.4\" # root BOM"));
        assertEquals(memberBefore, Files.readString(member.resolve("zolt.toml")));
        assertTrue(result.stdout().contains("\"manifestPath\": \"zolt.toml\""));
        assertTrue(result.stdout().contains("\"changedFiles\": [\n    \"zolt.toml\""));
        assertFalse(Files.exists(root.resolve("zolt.lock")));
    }


    @Test
    void rootPlatformUpdateRegeneratesTheWorkspaceRootLock() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addRepositoryArtifacts(repository, "5.10.2");
            addRepositoryArtifacts(repository, "5.11.4");
            Path root = writeWorkspace(
                    tempDir.resolve("resolved"),
                    "zolt.toml",
                    "\n[repositories.local]\nurl = \"" + repository.baseUri() + "\"\n",
                    "5.10.2");
            writeMember(root, "\n[dependencies]\n\"com.example:lib\" = \"1.0.0\"\n");
            UpdateTarget target = rootTarget(root.resolve("zolt.toml"), "zolt.toml");
            Path cache = tempDir.resolve("resolved-cache");
            new WorkspaceResolveService(new ResolveService()).resolve(root, cache, false, false);
            String lockBefore = Files.readString(root.resolve("zolt.lock"));

            Result result = run(
                    root,
                    new ResolveService(),
                    exactArgs(
                            target,
                            "5.11.4",
                            "--cache-root",
                            cache.toString()));

            assertEquals(0, result.exitCode(), () -> "stdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr());
            assertTrue(result.stdout().contains("\"resolved\": true"), result.stdout());
            assertTrue(result.stdout().contains(
                    "\"changedFiles\": [\n    \"zolt.toml\",\n    \"zolt.lock\""), result.stdout());
            String lock = Files.readString(root.resolve("zolt.lock"));
            assertFalse(lockBefore.equals(lock), lock);
            assertTrue(lock.contains("com.example:lib"), lock);
        }
    }

    @Test
    void failedRootResolutionRestoresManifestAndKeepsLockAbsent() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path root = writeWorkspace(
                    tempDir.resolve("rollback"),
                    "zolt.toml",
                    "\n[repositories.local]\nurl = \"" + repository.baseUri() + "\"\n",
                    "5.10.2");
            writeMember(root, "\n[dependencies]\n\"com.example:lib\" = \"1.0.0\"\n");
            String manifestBefore = Files.readString(root.resolve("zolt.toml"));
            UpdateTarget target = rootTarget(root.resolve("zolt.toml"), "zolt.toml");

            Result result = run(
                    root,
                    new ResolveService(),
                    exactArgs(
                            target,
                            "5.11.4",
                            "--cache-root",
                            tempDir.resolve("rollback-cache").toString()));

            assertEquals(1, result.exitCode());
            assertEquals(manifestBefore, Files.readString(root.resolve("zolt.toml")));
            assertFalse(Files.exists(root.resolve("zolt.lock")));
            Path journals = root.resolve(".zolt/manifest-edits");
            if (Files.isDirectory(journals)) {
                try (var entries = Files.list(journals)) {
                    assertEquals(0, entries.count());
                }
            }
        }
    }

    @Test
    void executionRevalidatesRootPlatformAndRecognizesConcurrentDestination() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("revalidated"), "zolt.toml", "", "5.10.2");
        writeMember(root);
        Path manifest = root.resolve("zolt.toml");
        UpdateTarget target = rootTarget(manifest, "zolt.toml");
        Runnable concurrent = () -> replace(manifest, "5.10.2", "5.11.4");

        Result result = run(
                root,
                null,
                concurrent,
                exactArgs(target, "5.11.4", "--no-resolve"));

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"from\": \"5.11.4\""), result.stdout());
        assertTrue(result.stdout().contains("\"changed\": false"), result.stdout());
        assertTrue(result.stdout().contains("\"applied\": false"), result.stdout());
        assertFalse(Files.exists(root.resolve("zolt.lock")));
    }

    @Test
    void executionFailsClosedWhenRootPlatformTargetDisappears() throws IOException {
        Path root = writeWorkspace(tempDir.resolve("disappeared"), "zolt.toml", "", "5.10.2");
        writeMember(root);
        Path manifest = root.resolve("zolt.toml");
        UpdateTarget target = rootTarget(manifest, "zolt.toml");
        Runnable concurrent = () -> replace(manifest, "org.junit:junit-bom", "org.junit:junit-platform");

        Result result = run(
                root,
                null,
                concurrent,
                exactArgs(target, "5.11.4", "--no-resolve"));

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Unknown Zolt update target"), result.stdout());
        assertTrue(Files.readString(manifest).contains("org.junit:junit-platform"));
        assertFalse(Files.exists(root.resolve("zolt.lock")));
    }

    private UpdateTarget rootTarget(Path manifest, String manifestPath) {
        return catalog.collect(LOADER.document(manifest).authored(), manifestPath, "zolt.lock").stream()
                .filter(target -> target.surface() == OutdatedSurface.PLATFORM)
                .findFirst()
                .orElseThrow();
    }

    private Result run(Path directory, ResolveService resolveService, String... arguments) {
        return run(directory, resolveService, () -> {}, arguments);
    }

    private Result run(
            Path directory,
            ResolveService resolveService,
            Runnable beforeExecution,
            String... arguments) {
        VersionDiscovery forbiddenDiscovery = (repositories, group, artifact, offline) -> {
            throw new AssertionError("Exact update must not perform metadata discovery.");
        };
        UpdateCommand command = new UpdateCommand(
                MANIFESTS,
                resolveService,
                new UpdateEngine(forbiddenDiscovery),
                beforeExecution);
        CommandLine commandLine = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setExecutionExceptionHandler((exception, parsedCommandLine, parseResult) -> 1);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));
        List<String> full = new ArrayList<>(List.of(arguments));
        full.add("--directory");
        full.add(directory.toString());
        int exitCode = commandLine.execute(full.toArray(String[]::new));
        return new Result(exitCode, stdout.toString(), stderr.toString());
    }

    private static String[] exactArgs(UpdateTarget target, String version, String... extra) {
        List<String> arguments = new ArrayList<>(List.of(
                "--target-id", target.targetId().toString(),
                "--to", version,
                "--format", "json",
                "--schema-version", "2"));
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    private static Path writeWorkspace(
            Path root,
            String filename,
            String policy,
            String platformVersion) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve(filename), """
                # retained workspace comment
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["apps/api"]
                %s
                [platforms]
                "org.junit:junit-bom" = "%s" # root BOM
                """.formatted(policy, platformVersion));
        return root;
    }

    private static Path writeMember(Path root) throws IOException {
        return writeMember(root, "");
    }

    private static Path writeMember(Path root, String body) throws IOException {
        Path member = root.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21
                %s
                """.formatted(body));
        return member;
    }

    private static void addRepositoryArtifacts(CliTestRepository repository, String bomVersion) {
        repository.addArtifact("org.junit", "junit-bom", bomVersion, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>%s</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """.formatted(bomVersion));
        repository.addArtifact("com.example", "lib", "1.0.0", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version>
                </project>
                """);
    }

    private static void replace(Path path, String before, String after) {
        try {
            Files.writeString(path, Files.readString(path).replace(before, after));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
