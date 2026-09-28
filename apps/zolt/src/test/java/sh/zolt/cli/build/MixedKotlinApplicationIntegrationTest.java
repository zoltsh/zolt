package sh.zolt.cli.build;

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
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler qualification for mixed Java/Kotlin application launch and packaging. */
final class MixedKotlinApplicationIntegrationTest {
    private static final String MAIN_CLASS = "com.example.ApplicationKt";
    private static final String EXPECTED_OUTPUT = "mixed-kotlin-one,two";

    @TempDir
    private Path tempDir;

    @Test
    void runsAndPackagesMixedApplicationFromASeededCache() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "one",
                    "two");
            assertApplicationRun(run, "Ran " + MAIN_CLASS);

            Path jar = project.resolve("target/mixed-kotlin-application-0.1.0.jar");
            Path runtimeClasspath = project.resolve(
                    "target/mixed-kotlin-application-0.1.0.runtime-classpath");
            CommandResult thinPackage = execute(
                    "package",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, thinPackage.exitCode(), thinPackage.stderr());
            assertTrue(
                    thinPackage.stdout().contains("Run with dependencies: zolt run-package -- [args]"),
                    thinPackage.stdout());
            assertThinPackage(jar, runtimeClasspath);

            CommandResult runPackage = execute(
                    "run-package",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString(),
                    "--",
                    "one",
                    "two");
            assertApplicationRun(runPackage, "Ran packaged " + MAIN_CLASS);
            assertTrue(runPackage.stdout().contains("→ from " + jar), runPackage.stdout());
            assertThinPackage(jar, runtimeClasspath);

            CommandResult uberPackage = execute(
                    "package",
                    "--mode", "uber-jar",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, uberPackage.exitCode(), uberPackage.stderr());
            assertTrue(
                    uberPackage.stdout().contains("Run as a self-contained jar: java -jar " + jar + " [args]"),
                    uberPackage.stdout());
            assertUberPackage(jar);

            ProcessResult directRun = runJar(jar, "one", "two");
            assertEquals(0, directRun.exitCode(), directRun.output());
            assertEquals(List.of(EXPECTED_OUTPUT), directRun.output().lines().toList());
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "resolved application commands must not contact the repository");
        }
    }

    private static void assertApplicationRun(CommandResult result, String summary) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(EXPECTED_OUTPUT), result.stdout());
        assertTrue(result.stdout().contains(summary), result.stdout());
    }

    private static void assertThinPackage(Path jarPath, Path runtimeClasspath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertApplicationInventory(jar);
            assertNull(jar.getEntry("kotlin/Unit.class"));
            assertCompilerClosureAbsent(jar);
        }
        List<String> entries = Files.readAllLines(runtimeClasspath).stream()
                .filter(entry -> !entry.isBlank())
                .toList();
        assertTrue(
                entries.stream().anyMatch(entry -> entry.endsWith("kotlin-stdlib-2.2.0.jar")),
                entries.toString());
        assertTrue(entries.stream().anyMatch(entry -> entry.endsWith("annotations-13.0.jar")), entries.toString());
        assertFalse(
                entries.stream().anyMatch(MixedKotlinApplicationIntegrationTest::isCompilerClosure),
                entries.toString());
    }

    private static void assertUberPackage(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertApplicationInventory(jar);
            assertNotNull(jar.getEntry("kotlin/Unit.class"));
            assertNotNull(jar.getEntry("org/jetbrains/annotations/NotNull.class"));
            assertCompilerClosureAbsent(jar);
        }
    }

    private static void assertApplicationInventory(JarFile jar) throws IOException {
        assertEquals(MAIN_CLASS, jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
        assertNotNull(jar.getEntry("com/example/ApplicationKt.class"));
        assertNotNull(jar.getEntry("com/example/KotlinApi.class"));
        assertNotNull(jar.getEntry("com/example/JavaBridge.class"));
        assertTrue(jar.stream().anyMatch(entry -> entry.getName().startsWith("META-INF/")
                && entry.getName().endsWith(".kotlin_module")));
    }

    private static void assertCompilerClosureAbsent(JarFile jar) {
        assertNull(jar.getEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"));
        assertNull(jar.getEntry("org/jetbrains/kotlin/daemon/common/CompileService.class"));
        assertNull(jar.getEntry("kotlin/reflect/jvm/internal/ReflectionFactoryImpl.class"));
        assertNull(jar.getEntry("kotlin/script/templates/standard/ScriptTemplateWithArgs.class"));
        assertNull(jar.getEntry("kotlinx/coroutines/Job.class"));
    }

    private static boolean isCompilerClosure(String entry) {
        return entry.contains("kotlin-compiler")
                || entry.contains("kotlin-daemon")
                || entry.contains("kotlin-reflect")
                || entry.contains("kotlin-script-runtime")
                || entry.contains("kotlinx-coroutines");
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "mixed-kotlin-application"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "%s"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                [toolchain.kotlin]
                version = "%s"

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                MAIN_CLASS,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(project.resolve("src/main/kotlin/com/example/Application.kt"), """
                package com.example

                object KotlinApi {
                    @JvmStatic
                    fun word(): String = "kotlin"
                }

                fun main(args: Array<String>) {
                    println(JavaBridge.message(args))
                }
                """);
        Files.writeString(project.resolve("src/main/java/com/example/JavaBridge.java"), """
                package com.example;

                public final class JavaBridge {
                    private JavaBridge() {}

                    public static String message(String[] arguments) {
                        return "mixed-" + KotlinApi.word() + "-" + String.join(",", arguments);
                    }
                }
                """);
    }

    private static ProcessResult runJar(Path jar, String... arguments) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        List<String> command = new java.util.ArrayList<>();
        command.add(java.toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out running mixed Kotlin uber JAR " + jar);
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
