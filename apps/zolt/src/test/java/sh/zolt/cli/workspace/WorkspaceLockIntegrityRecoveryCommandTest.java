package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPackageCachePath;
import sh.zolt.lockfile.LockPackagePathKind;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end online repair and offline refusal for workspace cache integrity. */
final class WorkspaceLockIntegrityRecoveryCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void onlineBuildRepairsCorruptAndMissingLockedArtifactKinds() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepare(repository);
            List<Path> artifacts = List.of(
                    artifact("shared", LockPackagePathKind.JAR),
                    artifact("shared", LockPackagePathKind.POM),
                    artifact("classified", LockPackagePathKind.JAR),
                    artifact("descriptor", LockPackagePathKind.SECONDARY));
            for (int index = 0; index < artifacts.size(); index++) {
                Path artifact = artifacts.get(index);
                byte[] expected = Files.readAllBytes(artifact);
                if (index % 2 == 0) {
                    Files.writeString(artifact, "corrupt workspace cache bytes");
                } else {
                    Files.delete(artifact);
                }

                assertSuccess(build(false, false));
                assertArrayEquals(expected, Files.readAllBytes(artifact), artifact.toString());
            }
        }
    }

    @Test
    void offlineBuildFailsClosedAndLeavesCorruptBytesUntouched() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepare(repository);
            Path artifact = artifact("descriptor", LockPackagePathKind.SECONDARY);
            Files.writeString(artifact, "corrupt secondary bytes");

            CommandResult result = build(true, false);

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Offline mode found corrupt cached"), result.stderr());
            assertTrue(result.stderr().contains("com.example:descriptor:1.0.0"), result.stderr());
            assertEquals("corrupt secondary bytes", Files.readString(artifact));
        }
    }

    @Test
    void oneFreshnessHashPerDistinctPathServesEveryWorkspaceMember() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepare(repository);

            CommandResult result = build(false, true);

            assertSuccess(result);
            String compile = result.stderr().lines()
                    .filter(line -> line.contains("\"phase\":\"compile workspace members\""))
                    .findFirst()
                    .orElseThrow();
            assertEquals(distinctArtifactPaths(), integerAttribute(compile, "artifactIntegrityHashes"));
            assertTrue(integerAttribute(compile, "artifactIntegrityCacheHits") > 0);
        }
    }

    private void prepare(CliTestRepository repository) throws IOException {
        addArtifact(repository, "shared");
        addArtifact(repository, "classified");
        repository.addClassifiedArtifact("com.example", "classified", "1.0.0", "linux", "jar");
        addArtifact(repository, "descriptor");
        repository.addTypedArtifact("com.example", "descriptor", "1.0.0", "properties");
        Files.createDirectories(root());
        Files.writeString(root().resolve("zolt.toml"), """
                [workspace]
                name = "integrity"
                members = ["apps/one", "apps/two"]

                [repositories]
                test = "%s"
                """.formatted(repository.baseUri()));
        for (String member : List.of("apps/one", "apps/two")) {
            Path directory = root().resolve(member);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("zolt.toml"), memberConfig(directory.getFileName().toString()) + """

                    [dependencies]
                    "com.example:shared" = "1.0.0"
                    "com.example:classified" = { version = "1.0.0", classifier = "linux" }

                    [runtime.dependencies]
                    "com.example:descriptor" = { version = "1.0.0", type = "properties" }
                    """);
            Path source = directory.resolve("src/main/java/com/example/App.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package com.example; public final class App {}\n");
        }
        assertSuccess(execute(
                "resolve", "--workspace",
                "--cwd", root().resolve("apps/one").toString(),
                "--cache-root", cache().toString()));
    }

    private static void addArtifact(CliTestRepository repository, String artifact) {
        repository.addArtifact("com.example", artifact, "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(artifact));
    }

    private CommandResult build(boolean offline, boolean timings) {
        var arguments = new java.util.ArrayList<>(List.of("build", "--workspace", "--all"));
        if (offline) {
            arguments.add("--offline");
        }
        if (timings) {
            arguments.addAll(List.of("--timings", "--timings-format", "json"));
        }
        arguments.addAll(List.of(
                "--cwd", root().resolve("apps/one").toString(),
                "--cache-root", cache().toString()));
        return execute(arguments.toArray(String[]::new));
    }

    private Path artifact(String artifact, LockPackagePathKind kind) {
        LockPackage locked = lockfile().packages().stream()
                .filter(candidate -> candidate.packageId().equals(new PackageId("com.example", artifact)))
                .findFirst()
                .orElseThrow();
        return LockPackageCachePath.path(locked, kind).orElseThrow().resolveWithin(cache());
    }

    private int distinctArtifactPaths() {
        Set<Path> paths = new LinkedHashSet<>();
        for (LockPackage locked : lockfile().packages()) {
            for (LockPackagePathKind kind : EnumSet.allOf(LockPackagePathKind.class)) {
                LockPackageCachePath.path(locked, kind)
                        .map(relative -> relative.resolveWithin(cache()))
                        .ifPresent(paths::add);
            }
        }
        return paths.size();
    }

    private sh.zolt.lockfile.ZoltLockfile lockfile() {
        return new ZoltLockfileReader().read(root().resolve("zolt.lock"));
    }

    private static int integerAttribute(String json, String key) {
        String prefix = "\"" + key + "\":\"";
        String value = json.substring(json.indexOf(prefix) + prefix.length());
        return Integer.parseInt(value.substring(0, value.indexOf('"')));
    }

    private static void assertSuccess(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
    }

    private Path root() {
        return tempDir.resolve("workspace");
    }

    private Path cache() {
        return tempDir.resolve("cache");
    }
}
