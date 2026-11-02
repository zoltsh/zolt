package sh.zolt.cli.build.kotlin.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for the explicit Kotlin protected-package opt-in. */
final class KotlinProtectedPackageIntegrationTest {
    private static final String FLAG = "-Xallow-kotlin-package";
    private static final FileTime WARM_SENTINEL = FileTime.fromMillis(946_684_800_000L);

    @TempDir
    private Path tempDir;

    @Test
    void rejectsProtectedPackageByDefaultAndCompilesOnlyWithExplicitOptInOffline()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult rejected = build(project, offlineCache);
            assertEquals(1, rejected.exitCode(), combined(rejected));
            assertTrue(
                    rejected.stderr().contains(
                            "only the Kotlin standard library is allowed to use the 'kotlin' package"),
                    rejected.stderr());

            replace(
                    project.resolve("zolt.toml"),
                    "# protected-package",
                    "[compiler]\nargs = [\"" + FLAG + "\"]");
            CommandResult accepted = build(project, offlineCache);
            assertEquals(0, accepted.exitCode(), combined(accepted));
            Path mainClass = project.resolve("target/classes/kotlin/zoltprobe/Main.class");
            assertTrue(Files.isRegularFile(mainClass));

            Files.setLastModifiedTime(mainClass, WARM_SENTINEL);
            CommandResult warm = build(project, offlineCache);
            assertEquals(0, warm.exitCode(), combined(warm));
            assertEquals(WARM_SENTINEL, Files.getLastModifiedTime(mainClass));

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString());
            assertEquals(0, run.exitCode(), combined(run));
            assertTrue(run.stdout().contains("protected-kotlin-package"), run.stdout());
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "protected-package commands must remain cache-only after resolve");
        }
    }

    private static CommandResult build(Path project, Path cache) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--no-progress",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeProject(Path project, CliTestRepository repository) throws Exception {
        Path source = project.resolve("src/main/kotlin/kotlin/zoltprobe/Main.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-protected-package"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "kotlin.zoltprobe.Main"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                # protected-package

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(source, """
                package kotlin.zoltprobe

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println("protected-kotlin-package")
                    }
                }
                """);
    }

    private static void replace(Path path, String before, String after) throws Exception {
        Files.writeString(path, Files.readString(path).replace(before, after));
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
