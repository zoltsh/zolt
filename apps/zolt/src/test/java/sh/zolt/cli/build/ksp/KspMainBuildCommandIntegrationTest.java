package sh.zolt.cli.build.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** CLI boundary proof for locked offline KSP2 generation, compilation, resources, and warm reuse. */
final class KspMainBuildCommandIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesBuildsRunsAndReusesKspMainOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KspCliFixture.publish(repository, tempDir.resolve("processor"));
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");

            assertEquals(0, resolve.exitCode(), resolve.stderr());
            String lock = Files.readString(project.resolve("zolt.lock"));
            assertTrue(lock.contains("id = \"com.google.devtools.ksp:symbol-processing-aa\""));
            assertTrue(lock.contains("toolGroups = [\"ksp:ksp:engine\"]"));
            assertTrue(lock.contains("id = \"com.example:ksp-cli-processor\""));
            assertTrue(lock.contains("toolGroups = [\"ksp:ksp:processors\"]"));

            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");

            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());

            CommandResult first = build(project, offlineCache);

            assertEquals(0, first.exitCode(), first.stderr());
            assertTiming(first, "full");
            Path generated = project.resolve("target/generated/ksp/main/symbols");
            assertTrue(Files.isRegularFile(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
            assertTrue(Files.isRegularFile(
                    generated.resolve("java/com/example/GeneratedJavaMessage.java")));
            assertEquals(
                    "cli-ksp-resource\n",
                    Files.readString(generated.resolve("resources/META-INF/ksp-cli.txt")));
            assertEquals(
                    "cli-ksp-resource\n",
                    Files.readString(project.resolve("target/classes/META-INF/ksp-cli.txt")));
            assertTrue(Files.isRegularFile(
                    project.resolve("target/classes/com/example/GeneratedKspMessage.class")));
            assertTrue(Files.isRegularFile(
                    project.resolve("target/classes/com/example/GeneratedJavaMessage.class")));

            CommandResult warm = build(project, offlineCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTiming(warm, "skipped");

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");

            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("cli-ksp-cli-ksp"), run.stdout());

            CommandResult packaged = execute(
                    "package",
                    "--mode", "uber-jar",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");
            Path jarPath = project.resolve("target/ksp-cli-0.1.0.jar");

            assertEquals(0, packaged.exitCode(), packaged.stderr());
            assertPackage(jarPath);
            ProcessResult directRun = runJar(jarPath);
            assertEquals(0, directRun.exitCode(), directRun.output());
            assertEquals(List.of("cli-ksp-cli-ksp"), directRun.output().lines().toList());

            CommandResult clean = execute(
                    "clean",
                    "--cwd", project.toString(),
                    "--no-progress");

            assertEquals(0, clean.exitCode(), clean.stderr());
            assertTrue(Files.notExists(project.resolve("target")));
            assertTrue(Files.isRegularFile(project.resolve("zolt.lock")));
            assertTrue(Files.isRegularFile(
                    project.resolve("src/main/kotlin/com/example/Main.kt")));

            CommandResult rebuilt = build(project, offlineCache);
            assertEquals(0, rebuilt.exitCode(), rebuilt.stderr());
            assertTiming(rebuilt, "full");
            assertTrue(Files.isRegularFile(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
            assertEquals(
                    "cli-ksp-resource\n",
                    Files.readString(project.resolve("target/classes/META-INF/ksp-cli.txt")));
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "locked cache-only KSP commands must not contact the repository");
        }
    }

    private static CommandResult build(Path project, Path cache) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString(),
                "--no-progress");
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Path source = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "ksp-cli"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [generated.tools.ksp]
                version = "%s"
                coordinates = [
                    { coordinate = "%s:%s", version = "%s" },
                ]

                [generated.main.symbols]
                kind = "ksp"
                options = { "fixture.message" = "cli-ksp", "fixture.requireSymbols" = "com.example.Main" }
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KspCliFixture.KSP_VERSION,
                KspCliFixture.PROCESSOR_GROUP,
                KspCliFixture.PROCESSOR_ARTIFACT,
                KspCliFixture.PROCESSOR_VERSION));
        Files.writeString(source, """
                package com.example

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(GeneratedKspMessage.value() + "-" + GeneratedJavaMessage.value())
                    }
                }
                """);
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationMode\":\"" + mode + "\""), line);
    }

    private static void assertPackage(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/Main.class"));
            assertNotNull(jar.getEntry("com/example/GeneratedKspMessage.class"));
            assertNotNull(jar.getEntry("com/example/GeneratedJavaMessage.class"));
            assertNotNull(jar.getEntry("META-INF/ksp-cli.txt"));
            assertNotNull(jar.getEntry("kotlin/Unit.class"));
            assertNull(jar.getEntry("com/google/devtools/ksp/cmdline/KSPJvmMain.class"));
            assertNull(jar.getEntry(
                    "com/google/devtools/ksp/processing/SymbolProcessorProvider.class"));
        }
    }

    private static ProcessResult runJar(Path jarPath) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        Process process = new ProcessBuilder(java.toString(), "-jar", jarPath.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running KSP uber JAR " + jarPath);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
