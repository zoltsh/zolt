package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Kotlin metadata strict-version semantics. */
final class KotlinTestStrictMetadataIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void marksTestMetadataOfflineAndInvalidatesWarmCompilation() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeSource(project);
            writeManifest(project, repository, false);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult baseline = test(project, cache);
            assertSuccessful(baseline);
            assertTiming(baseline, "\"testCompilationMode\":\"full\"");
            assertEquals(48, metadataExtraInt(project, cache));

            CommandResult baselineWarm = test(project, cache);
            assertSuccessful(baselineWarm);
            assertTiming(baselineWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, true);
            CommandResult strict = test(project, cache);
            assertSuccessful(strict);
            assertTiming(strict, "\"testCompilationMode\":\"full\"");
            assertEquals(56, metadataExtraInt(project, cache));

            CommandResult strictWarm = test(project, cache);
            assertSuccessful(strictWarm);
            assertTiming(strictWarm, "\"testCompilationMode\":\"skipped\"");

            writeManifest(project, repository, false);
            CommandResult restored = test(project, cache);
            assertSuccessful(restored);
            assertTiming(restored, "\"testCompilationMode\":\"full\"");
            assertEquals(48, metadataExtraInt(project, cache));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains("Tests passed"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile test sources timing in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }

    private static int metadataExtraInt(Path project, Path cache) throws Exception {
        URL[] urls = {
            project.resolve("target/test-classes").toUri().toURL(),
            kotlinStdlib(cache).toUri().toURL()
        };
        try (URLClassLoader loader = new URLClassLoader(
                urls,
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("com.example.MetadataTestApi", true, loader);
            Class<? extends Annotation> metadataType = Class.forName(
                            "kotlin.Metadata",
                            true,
                            loader)
                    .asSubclass(Annotation.class);
            Annotation metadata = type.getAnnotation(metadataType);
            assertNotNull(metadata);
            return (int) metadataType.getMethod("xi").invoke(metadata);
        }
    }

    private static Path kotlinStdlib(Path cache) throws IOException {
        String fileName = "kotlin-stdlib-" + KotlinCompilerCliFixture.KOTLIN_VERSION + ".jar";
        try (var paths = Files.walk(cache)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Kotlin stdlib was not cached under " + cache));
        }
    }

    private static void writeSource(Path project) throws IOException {
        Path source = project.resolve("src/test/kotlin/com/example/StrictMetadataTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                object MetadataTestApi {
                    fun message(): String = "metadata"
                }

                class StrictMetadataTest {
                    @Test
                    fun preservesRuntimeBehavior() {
                        assertEquals("metadata", MetadataTestApi.message())
                    }
                }
                """);
    }

    private static void writeManifest(
            Path project,
            CliTestRepository repository,
            boolean strictMetadata) throws IOException {
        String compilerArguments = strictMetadata
                ? "\"-parameters\", \"-Xgenerate-strict-metadata-version\""
                : "\"-parameters\"";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-test-strict-metadata"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = [%s]

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies.test]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                compilerArguments,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }
}
