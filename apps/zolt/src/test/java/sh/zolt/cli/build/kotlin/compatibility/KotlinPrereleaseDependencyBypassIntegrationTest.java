package sh.zolt.cli.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;
import sh.zolt.cli.build.fixture.KotlinFixtureCompiler;

/** Canonical CLI/worker proof for the explicit Kotlin prerelease-dependency bypass. */
@Isolated("invokes the embedded Kotlin compiler while creating the dependency fixture")
final class KotlinPrereleaseDependencyBypassIntegrationTest {
    private static final String FLAG = "-Xskip-prerelease-check";
    private static final String GROUP = "com.example";
    private static final String ARTIFACT = "prerelease-kotlin-api";
    private static final String VERSION = "1.0.0";
    private static final FileTime WARM_SENTINEL = FileTime.fromMillis(946_684_800_000L);

    @TempDir
    private Path tempDir;

    @Test
    void rejectsPrereleaseDependencyByDefaultAndCompilesOnlyWithExplicitBypassOffline()
            throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            publishPrereleaseProvider(repository, tempDir.resolve("prerelease-provider"));
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
            assertTrue(rejected.stderr().contains("pre-release declarations"), rejected.stderr());
            assertTrue(rejected.stderr().contains(FLAG), rejected.stderr());

            replace(
                    project.resolve("zolt.toml"),
                    "# prerelease-bypass",
                    "[compiler]\nargs = [\"" + FLAG + "\"]");
            CommandResult accepted = build(project, offlineCache);
            assertEquals(0, accepted.exitCode(), combined(accepted));
            Path mainClass = project.resolve("target/classes/com/example/Main.class");
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
            assertTrue(run.stdout().contains("prerelease-metadata"), run.stdout());
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "prerelease-bypass commands must remain cache-only after resolve");
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

    private static void publishPrereleaseProvider(
            CliTestRepository repository,
            Path workDirectory) throws Exception {
        Path jar = providerJar(workDirectory);
        repository.addArtifact(
                GROUP,
                ARTIFACT,
                VERSION,
                """
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </project>
                        """.formatted(GROUP, ARTIFACT, VERSION),
                Files.readAllBytes(jar));
    }

    private static Path providerJar(Path workDirectory) throws Exception {
        Path source = workDirectory.resolve("src/prerelease/PrereleaseApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package prerelease

                object PrereleaseApi {
                    @JvmStatic
                    fun value(): String = "prerelease-metadata"
                }
                """);
        Path classes = workDirectory.resolve("classes");
        Files.createDirectories(classes);
        compile(source, classes);
        patchPrereleaseBit(classes.resolve("prerelease/PrereleaseApi.class"));
        Path jar = workDirectory.resolve(ARTIFACT + "-" + VERSION + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(
                        classes.relativize(file).toString().replace(File.separatorChar, '/'));
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return jar;
    }

    private static void compile(Path source, Path classes) throws Exception {
        Path stdlib = KotlinFixtureCompiler.runtimeJar(
                "kotlin-stdlib", KotlinCompilerCliFixture.KOTLIN_VERSION);
        KotlinFixtureCompiler.compile(
                "prerelease Kotlin metadata fixture",
                List.of(
                        "-no-stdlib",
                        "-no-reflect",
                        "-classpath", stdlib.toString(),
                        "-jvm-target", Integer.toString(Runtime.version().feature()),
                        "-module-name", "prerelease_provider",
                        "-d", classes.toString(),
                        source.toString()));
    }

    private static void patchPrereleaseBit(Path classFile) throws Exception {
        byte[] bytes = Files.readAllBytes(classFile);
        int constantPoolCount = unsignedShort(bytes, 8);
        int offset = 10;
        int patched = 0;
        for (int index = 1; index < constantPoolCount; index++) {
            int tag = bytes[offset++] & 0xff;
            switch (tag) {
                case 1 -> offset += 2 + unsignedShort(bytes, offset);
                case 3 -> {
                    if (integer(bytes, offset) == 48) {
                        putInteger(bytes, offset, 50);
                        patched++;
                    }
                    offset += 4;
                }
                case 4 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 15 -> offset += 3;
                default -> throw new IllegalStateException(
                        "Unknown constant-pool tag " + tag + " in " + classFile);
            }
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "Expected one Kotlin metadata extra-int constant in " + classFile
                            + ", patched " + patched);
        }
        Files.write(classFile, bytes);
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int integer(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static void putInteger(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void writeProject(Path project, CliTestRepository repository) throws Exception {
        Path source = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-prerelease-bypass"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                # prerelease-bypass

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "%s:%s" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                GROUP,
                ARTIFACT,
                VERSION));
        Files.writeString(source, """
                package com.example

                import prerelease.PrereleaseApi

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(PrereleaseApi.value())
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
