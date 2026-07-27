package sh.zolt.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof that {@code ZOLT_USER_HOME} redirects the whole user-global tree. The variable can
 * only be set for a real process, so these run the CLI entry point in a child JVM off the current test
 * classpath and assert on what the user actually sees: the config that gets read, the cache root that
 * gets reported, and a user-global directory that gets written.
 */
final class UserGlobalHomeOverrideEndToEndTest {
    @TempDir
    private Path work;

    @Test
    void configIsReadFromAndCachesResolveUnderTheOverride() throws Exception {
        Path override = Files.createDirectories(work.resolve("zolt-user-home"));
        Files.writeString(override.resolve("config.toml"), """
                version = 1

                [repository]
                downloadConcurrency = 3
                """);

        CliProcessResult result = runCli(override.toString(), "config", "show");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains("User global config: " + override.resolve("config.toml")),
                result.stdout());
        assertTrue(result.stdout().contains("config file: present"), result.stdout());
        assertTrue(
                result.stdout().contains("cache.root: " + override.resolve("cache")),
                result.stdout());
        // The override is honored as the built-in default, not as an explicit --config flag.
        assertTrue(result.stdout().contains("config path source: built-in default"), result.stdout());
        // The redirected config really was parsed, rather than falling back to defaults.
        assertTrue(result.stdout().contains("repository.downloadConcurrency: 3"), result.stdout());
    }

    @Test
    void userGlobalWritesLandUnderTheOverride() throws Exception {
        Path override = work.resolve("write-home");

        CliProcessResult result = runCli(override.toString(), "shims", "install");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.isRegularFile(override.resolve("shims").resolve("java")), result.stdout());
        assertTrue(result.stdout().contains(override.resolve("shims").toString()), result.stdout());
    }

    @Test
    void relativeOverrideFailsWithAnActionableMessageNamingTheVariable() throws Exception {
        CliProcessResult result = runCli("relative-user-home", "config", "show");

        assertNotEquals(0, result.exitCode(), result.stdout());
        String combined = result.stdout() + result.stderr();
        assertTrue(combined.contains("ZOLT_USER_HOME"), combined);
        assertTrue(combined.contains("relative-user-home"), combined);
        assertTrue(combined.contains("absolute directory path"), combined);
    }

    @Test
    void withoutTheOverrideTheDefaultUserHomeStillApplies() throws Exception {
        CliProcessResult result = runCli(null, "config", "show");

        assertEquals(0, result.exitCode(), result.stderr());
        Path expected = Path.of(System.getProperty("user.home"), ".zolt", "config.toml")
                .toAbsolutePath()
                .normalize();
        assertTrue(result.stdout().contains("User global config: " + expected), result.stdout());
    }

    private CliProcessResult runCli(String userHomeOverride, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                javaExecutable().toString(),
                "-cp",
                childClasspath(),
                "sh.zolt.cli.ZoltCli"));
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Files.createDirectories(work.resolve("cwd")).toFile());
        Map<String, String> environment = builder.environment();
        environment.remove("ZOLT_USER_HOME");
        if (userHomeOverride != null) {
            environment.put("ZOLT_USER_HOME", userHomeOverride);
        }
        Path stdout = Files.createTempFile(work, "stdout", ".txt");
        Path stderr = Files.createTempFile(work, "stderr", ".txt");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());

        Process process = builder.start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("zolt " + String.join(" ", args) + " did not finish in time.");
        }
        return new CliProcessResult(process.exitValue(), read(stdout), read(stderr));
    }

    /**
     * The JUnit console launcher loads test and product classes through its own {@code URLClassLoader}
     * rather than {@code java.class.path}, so the child JVM's classpath is recovered from the loader
     * chain and unioned with the launcher's own classpath.
     */
    private static String childClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (ClassLoader loader = UserGlobalHomeOverrideEndToEndTest.class.getClassLoader();
                loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    fileEntry(url).ifPresent(entries::add);
                }
            }
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Optional<String> fileEntry(URL url) {
        try {
            return Optional.of(Path.of(url.toURI()).toAbsolutePath().normalize().toString());
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException exception) {
            return Optional.empty();
        }
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private record CliProcessResult(int exitCode, String stdout, String stderr) {
    }
}
