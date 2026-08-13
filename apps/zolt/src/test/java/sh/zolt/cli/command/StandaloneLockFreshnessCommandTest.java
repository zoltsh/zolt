package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Fresh-clone canaries for every standalone command family that shares {@link CommandLockfiles}. */
final class StandaloneLockFreshnessCommandTest {
    @TempDir
    private Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandsUsingFreshnessGate")
    void commandRematerializesJarMissingFromMatchingLock(
            String displayName,
            List<String> command) throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepareProject(repository);
            Path jar = LockPackageCachePath.path(lockedPackage(), LockPackagePathKind.JAR)
                    .orElseThrow()
                    .resolveWithin(cacheRoot());
            Files.delete(jar);

            CommandResult result = execute(command(command));

            assertEquals(0, result.exitCode(), displayName + ": " + result.stderr());
            assertTrue(Files.isRegularFile(jar), displayName);
        }
    }

    @Test
    void buildRematerializesCompletelyEmptyCacheFromMatchingLock() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepareProject(repository);
            LockPackage locked = lockedPackage();
            Path emptyCache = tempDir.resolve("empty-cache");
            Path jar = LockPackageCachePath.path(locked, LockPackagePathKind.JAR)
                    .orElseThrow()
                    .resolveWithin(emptyCache);
            Path pom = LockPackageCachePath.path(locked, LockPackagePathKind.POM)
                    .orElseThrow()
                    .resolveWithin(emptyCache);

            CommandResult result = execute(command(List.of("build"), emptyCache));

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(Files.isRegularFile(jar));
            assertTrue(Files.isRegularFile(pom));
        }
    }

    @Test
    void onlineBuildRepairsCorruptLockedBytes() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepareProject(repository);
            Path jar = LockPackageCachePath.path(lockedPackage(), LockPackagePathKind.JAR)
                    .orElseThrow()
                    .resolveWithin(cacheRoot());
            Files.writeString(jar, "corrupt bytes");

            CommandResult result = execute(command(List.of("build")));

            assertEquals(0, result.exitCode(), result.stderr());
            assertFalse(Files.readString(jar).equals("corrupt bytes"));
        }
    }

    @Test
    void buildRematerializesMissingClassifierJar() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepareProject(repository);
            Path classifierJar = LockPackageCachePath.path(
                            lockedPackage("native-dependency"), LockPackagePathKind.JAR)
                    .orElseThrow()
                    .resolveWithin(cacheRoot());
            Files.delete(classifierJar);

            CommandResult result = execute(command(List.of("build")));

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(Files.isRegularFile(classifierJar));
            assertTrue(classifierJar.getFileName().toString().contains("-linux.jar"));
        }
    }

    @Test
    void offlineBuildReportsExactMissingLockedArtifact() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepareProject(repository);
            Path jar = LockPackageCachePath.path(lockedPackage(), LockPackagePathKind.JAR)
                    .orElseThrow()
                    .resolveWithin(cacheRoot());
            Files.delete(jar);

            CommandResult result = execute(command(List.of("build", "--offline")));

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Offline mode requires cached JAR"), result.stderr());
            assertTrue(result.stderr().contains("com.example:dependency:1.0.0"), result.stderr());
            assertFalse(result.stderr().contains("integrity check failed"), result.stderr());
        }
    }

    @Test
    void offlineBuildReportsCorruptLockedArtifactWithoutRepairingIt() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            prepareProject(repository);
            Path jar = LockPackageCachePath.path(lockedPackage(), LockPackagePathKind.JAR)
                    .orElseThrow()
                    .resolveWithin(cacheRoot());
            Files.writeString(jar, "corrupt bytes");

            CommandResult result = execute(command(List.of("build", "--offline")));

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Offline mode found corrupt cached JAR"), result.stderr());
            assertTrue(result.stderr().contains("com.example:dependency:1.0.0"), result.stderr());
            assertEquals("corrupt bytes", Files.readString(jar));
        }
    }

    private static Stream<Arguments> commandsUsingFreshnessGate() {
        return Stream.of(
                Arguments.of("build", List.of("build")),
                Arguments.of("run", List.of("run")),
                Arguments.of("package", List.of("package")),
                Arguments.of("classpath", List.of("classpath", "runtime")),
                Arguments.of("test compile", List.of("test", "--compile-only")));
    }

    private void prepareProject(CliTestRepository repository) throws IOException {
        repository.addArtifact("com.example", "dependency", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>dependency</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        repository.addArtifact("com.example", "native-dependency", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>native-dependency</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        repository.addClassifiedArtifact(
                "com.example", "native-dependency", "1.0.0", "linux", "jar");
        Files.createDirectories(projectRoot());
        Files.writeString(projectRoot().resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [repositories]
                test = "%s"

                [dependencies]
                "com.example:dependency" = "1.0.0"
                "com.example:native-dependency" = { version = "1.0.0", classifier = "linux" }

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """.formatted(currentJavaMajorVersion(), repository.baseUri()));
        Path source = projectRoot().resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.print("ready");
                    }
                }
                """);

        CommandResult resolved = execute(command(List.of("resolve")));
        assertEquals(0, resolved.exitCode(), resolved.stderr());
    }

    private LockPackage lockedPackage() {
        return lockedPackage("dependency");
    }

    private LockPackage lockedPackage(String artifactId) {
        return new ZoltLockfileReader().read(projectRoot().resolve("zolt.lock")).packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(
                        new PackageId("com.example", artifactId)))
                .findFirst()
                .orElseThrow();
    }

    private String[] command(List<String> command) {
        return command(command, cacheRoot());
    }

    private String[] command(List<String> command, Path commandCacheRoot) {
        String[] arguments = new String[command.size() + 4];
        for (int index = 0; index < command.size(); index++) {
            arguments[index] = command.get(index);
        }
        arguments[command.size()] = "--cwd";
        arguments[command.size() + 1] = projectRoot().toString();
        arguments[command.size() + 2] = "--cache-root";
        arguments[command.size() + 3] = commandCacheRoot.toString();
        return arguments;
    }

    private Path projectRoot() {
        return tempDir.resolve("project");
    }

    private Path cacheRoot() {
        return tempDir.resolve("cache");
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && parts[0].equals("1") ? parts[1] : parts[0];
    }
}
