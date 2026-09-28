package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** CLI canary for hermetic Kotlin resolution, compilation, reuse, and launch. */
final class BuildCommandKotlinOfflineIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesBuildsRunsAndReusesRealKotlinMainOffline() throws Exception {
        Path qualifiedRoot = tempDir.resolve("Kotlin π workspace");
        Path projectDirectory = qualifiedRoot.resolve("project with spaces λ");
        Path onlineCache = qualifiedRoot.resolve("online artifact cache 例");
        Path offlineCache = qualifiedRoot.resolve("offline artifact cache 例");

        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.writeProject(projectDirectory, repository.baseUri());

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", onlineCache.toString());

            assertEquals(0, resolve.exitCode(), resolve.stderr());
            assertTrue(resolve.stdout().contains("wrote " + projectDirectory.resolve("zolt.lock")));
            String lock = Files.readString(projectDirectory.resolve("zolt.lock"));
            assertTrue(lock.contains("id = \"org.jetbrains.kotlin:kotlin-compiler-embeddable\""));
            assertTrue(lock.contains("scope = \"tool-kotlin\""));
            repository.clearAuthorizations();

            Files.move(onlineCache, offlineCache);
            byte[] lockBeforeOfflineResolve = Files.readAllBytes(projectDirectory.resolve("zolt.lock"));
            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());

            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());
            assertTrue(offlineResolve.stdout().contains("verified " + projectDirectory.resolve("zolt.lock")));
            assertArrayEquals(lockBeforeOfflineResolve, Files.readAllBytes(projectDirectory.resolve("zolt.lock")));

            CommandResult firstBuild = buildOffline(projectDirectory, offlineCache);
            Path classFile = projectDirectory.resolve("target/classes/com/example/Main.class");
            Path moduleFile = kotlinModule(projectDirectory);

            assertEquals(0, firstBuild.exitCode(), firstBuild.stderr());
            assertTrue(firstBuild.stdout().contains("Compiled 1 main source files"), firstBuild.stdout());
            assertTrue(Files.isRegularFile(classFile));
            assertTrue(Files.isRegularFile(moduleFile));

            CommandResult warmBuild = buildOffline(projectDirectory, offlineCache);

            assertEquals(0, warmBuild.exitCode(), warmBuild.stderr());
            assertTrue(warmBuild.stdout().contains("Skipped main compilation; inputs are unchanged"), warmBuild.stdout());

            KotlinCompilerCliFixture.writeSource(projectDirectory, "run");
            CommandResult run = execute(
                    "run",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());

            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("real-kotlin-run"), run.stdout());
            assertTrue(run.stdout().contains("Ran com.example.Main"), run.stdout());

            KotlinCompilerCliFixture.writeSource(projectDirectory, "thin");
            CommandResult thinPackage = execute(
                    "package",
                    "--no-build-cache",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());
            Path jarFile = projectDirectory.resolve("target/kotlin-cli-0.1.0.jar");
            Path runtimeClasspath = projectDirectory.resolve("target/kotlin-cli-0.1.0.runtime-classpath");

            assertEquals(0, thinPackage.exitCode(), thinPackage.stderr());
            assertTrue(thinPackage.stdout().contains("Packaged 2 compiled files as jar"), thinPackage.stdout());
            assertTrue(
                    thinPackage.stdout().contains("Run with dependencies: zolt run-package -- [args]"),
                    thinPackage.stdout());
            assertThinPackage(jarFile, runtimeClasspath);

            KotlinCompilerCliFixture.writeSource(projectDirectory, "thin-package");
            CommandResult runPackage = execute(
                    "run-package",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());

            assertEquals(0, runPackage.exitCode(), runPackage.stderr());
            assertTrue(runPackage.stdout().contains("real-kotlin-thin-package"), runPackage.stdout());
            assertTrue(runPackage.stdout().contains("Ran packaged com.example.Main"), runPackage.stdout());
            assertTrue(runPackage.stdout().contains("→ from " + jarFile), runPackage.stdout());
            assertThinPackage(jarFile, runtimeClasspath);

            KotlinCompilerCliFixture.writeSource(projectDirectory, "uber");
            CommandResult uberPackage = execute(
                    "package",
                    "--mode", "uber-jar",
                    "--no-build-cache",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());

            assertEquals(0, uberPackage.exitCode(), uberPackage.stderr());
            assertTrue(uberPackage.stdout().contains("compiled files as uber-jar"), uberPackage.stdout());
            assertTrue(uberPackage.stdout().contains(
                    "Run as a self-contained jar: java -jar " + jarFile + " [args]"), uberPackage.stdout());
            assertUberPackage(jarFile);
            ProcessResult uberRun = runJar(jarFile);
            assertEquals(0, uberRun.exitCode(), uberRun.output());
            assertEquals(List.of("real-kotlin-uber"), uberRun.output().lines().toList());

            byte[] classBeforeFailure = Files.readAllBytes(classFile);
            byte[] moduleBeforeFailure = Files.readAllBytes(moduleFile);
            Files.delete(KotlinCompilerCliFixture.compilerJar(offlineCache));
            KotlinCompilerCliFixture.writeSource(projectDirectory, "must-not-compile");

            CommandResult missingCompiler = buildOffline(projectDirectory, offlineCache);

            assertEquals(1, missingCompiler.exitCode());
            assertTrue(
                    missingCompiler.stderr().contains("Offline mode found corrupt cached JAR"),
                    missingCompiler.stderr());
            assertTrue(missingCompiler.stderr().contains(
                    "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + KotlinCompilerCliFixture.KOTLIN_VERSION),
                    missingCompiler.stderr());
            assertFalse(missingCompiler.stderr().contains("\tat "), missingCompiler.stderr());
            assertArrayEquals(classBeforeFailure, Files.readAllBytes(classFile));
            assertArrayEquals(moduleBeforeFailure, Files.readAllBytes(moduleFile));
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-seed cache-only commands must not contact the repository");
        }
    }

    private static CommandResult buildOffline(Path projectDirectory, Path cacheRoot) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--cwd", projectDirectory.toString(),
                "--cache-root", cacheRoot.toString());
    }

    private static Path kotlinModule(Path projectDirectory) throws IOException {
        Path metadataDirectory = projectDirectory.resolve("target/classes/META-INF");
        try (Stream<Path> paths = Files.list(metadataDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Kotlin module metadata was not compiled"));
        }
    }

    private static void assertThinPackage(Path jarPath, Path runtimeClasspath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/Main.class"));
            assertTrue(hasKotlinModule(jar));
            assertNull(jar.getEntry("kotlin/Unit.class"));
            assertCompilerClassesAbsent(jar);
        }
        List<String> runtimeEntries = Files.readAllLines(runtimeClasspath).stream()
                .filter(entry -> !entry.isBlank())
                .toList();
        assertEquals(2, runtimeEntries.size(), runtimeEntries.toString());
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.endsWith("kotlin-stdlib-2.2.0.jar")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.endsWith("annotations-13.0.jar")));
        assertFalse(runtimeEntries.stream().anyMatch(entry -> entry.contains("kotlin-compiler")));
    }

    private static void assertUberPackage(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/Main.class"));
            assertTrue(hasKotlinModule(jar));
            assertNotNull(jar.getEntry("kotlin/Unit.class"));
            assertNotNull(jar.getEntry("org/jetbrains/annotations/NotNull.class"));
            assertCompilerClassesAbsent(jar);
        }
    }

    private static boolean hasKotlinModule(JarFile jar) {
        return jar.stream().anyMatch(entry -> entry.getName().startsWith("META-INF/")
                && entry.getName().endsWith(".kotlin_module"));
    }

    private static void assertCompilerClassesAbsent(JarFile jar) {
        assertNull(jar.getEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"));
        assertNull(jar.getEntry("org/jetbrains/kotlin/daemon/common/CompileService.class"));
        assertNull(jar.getEntry("kotlin/reflect/jvm/internal/ReflectionFactoryImpl.class"));
        assertNull(jar.getEntry("kotlin/script/templates/standard/ScriptTemplateWithArgs.class"));
        assertNull(jar.getEntry("kotlinx/coroutines/Job.class"));
    }

    private static ProcessResult runJar(Path jarPath) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        Process process = new ProcessBuilder(java.toString(), "-jar", jarPath.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running Kotlin uber JAR " + jarPath);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
