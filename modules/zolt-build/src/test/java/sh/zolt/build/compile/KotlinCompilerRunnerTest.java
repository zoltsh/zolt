package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.Classpath;

final class KotlinCompilerRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void isolatesLauncherClosureAndUsesReleaseApiDeterministically() {
        List<List<String>> commands = new ArrayList<>();
        List<Path> argumentFiles = new ArrayList<>();
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            commands.add(command);
            Path argumentFile = argumentFile(command);
            argumentFiles.add(argumentFile);
            argumentContents.add(readString(argumentFile));
            return new KotlinCompilerRunner.ProcessResult(0, "compiled kotlin\n");
        });
        Path output = tempDir.resolve("classes");

        JavacResult result = runner.compile(
                Path.of("/managed-jdk/bin/java"),
                Path.of("/managed-jdk"),
                List.of(Path.of("src/Z.kt"), Path.of("src/A.kt")),
                new Classpath(List.of(Path.of("cache/compiler.jar"), Path.of("cache/coroutines.jar"))),
                new Classpath(List.of(Path.of("cache/kotlin-stdlib.jar"), Path.of("cache/dependency.jar"))),
                output,
                new KotlinCompilerRunner.Options("21", "demo_main", false));

        assertEquals(2, result.sourceCount());
        assertEquals("compiled kotlin\n", result.output());
        assertEquals(List.of(
                "/managed-jdk/bin/java",
                "-cp",
                "cache/compiler.jar:cache/coroutines.jar",
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"), commands.getFirst().subList(0, 4));
        assertEquals(5, commands.getFirst().size());
        assertEquals("@" + argumentFiles.getFirst(), commands.getFirst().getLast());
        assertTrue(argumentFiles.getFirst().isAbsolute());
        assertEquals(KotlinCompilerArgumentsFile.encode(List.of(
                "-no-stdlib",
                "-no-reflect",
                "-jdk-home",
                "/managed-jdk",
                "-Xjdk-release=21",
                "-classpath",
                "cache/kotlin-stdlib.jar:cache/dependency.jar",
                "-module-name",
                "demo_main",
                "-d",
                output.toString(),
                "src/A.kt",
                "src/Z.kt")), argumentContents.getFirst());
        assertFalse(Files.exists(argumentFiles.getFirst()));
        assertFalse(commands.getFirst().get(2).contains("dependency.jar"));
        assertFalse(argumentContents.getFirst().contains("compiler.jar"));
    }

    @Test
    void hostApiModeTargetsJavaEightWithoutPinningRelease() {
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentContents.add(readString(argumentFile(command)));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("host-classes"),
                new KotlinCompilerRunner.Options("8", "host_main", true));

        String arguments = argumentContents.getFirst();
        assertTrue(arguments.contains("\"-jvm-target\"\n\"1.8\"\n"));
        assertFalse(arguments.contains("-Xjdk-release="));
    }

    @Test
    void releaseApiOnJdkEightUsesTheSelectedJdkAndJvmTarget() {
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentContents.add(readString(argumentFile(command)));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk8/bin/java"),
                Path.of("/jdk8"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("jdk8-classes"),
                new KotlinCompilerRunner.Options("8", "jdk8_main", false, false));

        String arguments = argumentContents.getFirst();
        assertTrue(arguments.contains("\"-jdk-home\"\n\"/jdk8\"\n"));
        assertTrue(arguments.contains("\"-jvm-target\"\n\"1.8\"\n"));
        assertFalse(arguments.contains("-Xjdk-release="));
    }

    @Test
    void emitsJavaParametersExactlyOnceWhenRequested() {
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentContents.add(readString(argumentFile(command)));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("parameters-classes"),
                new KotlinCompilerRunner.Options(
                        "21", "parameters_main", false, true, true));

        List<String> arguments = argumentContents.getFirst().lines().toList();
        assertEquals(
                1,
                arguments.stream().filter("\"-java-parameters\""::equals).count(),
                arguments.toString());
    }

    @Test
    void passesTheOwnedMainOutputAsTheSoleFriendPath() {
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentContents.add(readString(argumentFile(command)));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(Path.of("src/Test.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("main.jar"))),
                tempDir.resolve("test-classes"),
                new KotlinCompilerRunner.Options("21", "demo_test", false)
                        .withFriendPath(Path.of("friends/main/../main")),
                KotlinCompilationScope.TEST);

        List<String> lines = argumentContents.getFirst().lines().toList();
        assertTrue(lines.contains("\"-Xfriend-paths=friends/main\""), lines.toString());
        assertEquals(
                1,
                lines.stream().filter(argument -> argument.startsWith("\"-Xfriend-paths=")).count());
    }

    @Test
    void emptySourceSetCreatesOutputWithoutStartingCompiler() {
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            throw new AssertionError("compiler must not run");
        });
        Path output = tempDir.resolve("empty");

        JavacResult result = runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(),
                new Classpath(List.of()),
                new Classpath(List.of()),
                output,
                new KotlinCompilerRunner.Options("21", "empty_main", false));

        assertEquals(0, result.sourceCount());
        assertTrue(java.nio.file.Files.isDirectory(output));
    }

    @Test
    void compilerFailurePreservesExitCodeAndDiagnostics() {
        List<Path> argumentFiles = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentFiles.add(argumentFile(command));
            return new KotlinCompilerRunner.ProcessResult(2, "internal compiler failure\n");
        });

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> runner.compile(
                        Path.of("/jdk/bin/java"),
                        Path.of("/jdk"),
                        List.of(Path.of("src/Main.kt")),
                        new Classpath(List.of(Path.of("compiler.jar"))),
                        new Classpath(List.of(Path.of("stdlib.jar"))),
                        tempDir.resolve("failed"),
                        new KotlinCompilerRunner.Options("21", "failed_main", false)));

        assertTrue(failure.getMessage().contains("exit code 2"));
        assertTrue(failure.getMessage().contains("internal compiler failure"));
        assertTrue(failure.getMessage().contains("[toolchain.kotlin]"));
        assertTrue(failure.getMessage().contains("[dependencies]"));
        assertFalse(Files.exists(argumentFiles.getFirst()));
    }

    @Test
    void removesArgumentFileWhenProcessRunnerThrows() {
        List<Path> argumentFiles = new ArrayList<>();
        IllegalStateException processFailure = new IllegalStateException("process failed before completion");
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentFiles.add(argumentFile(command));
            throw processFailure;
        });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> runner.compile(
                        Path.of("/jdk/bin/java"),
                        Path.of("/jdk"),
                        List.of(Path.of("src/Main.kt")),
                        new Classpath(List.of(Path.of("compiler.jar"))),
                        new Classpath(List.of(Path.of("stdlib.jar"))),
                        tempDir.resolve("runner-failed"),
                        new KotlinCompilerRunner.Options("21", "runner_failed_main", false)));

        assertSame(processFailure, failure);
        assertFalse(Files.exists(argumentFiles.getFirst()));
    }

    @Test
    void cleanupFailureAfterSuccessfulCompilerRunFailsClosed() throws IOException {
        List<Path> argumentFiles = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            Path argumentFile = argumentFile(command);
            argumentFiles.add(argumentFile);
            blockDeletion(argumentFile);
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        try {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> runner.compile(
                            Path.of("/jdk/bin/java"),
                            Path.of("/jdk"),
                            List.of(Path.of("src/Main.kt")),
                            new Classpath(List.of(Path.of("compiler.jar"))),
                            new Classpath(List.of(Path.of("stdlib.jar"))),
                            tempDir.resolve("cleanup-failed"),
                            new KotlinCompilerRunner.Options("21", "cleanup_failed_main", false)));

            assertTrue(failure.getMessage().contains("temporary Kotlin main compiler argument file"));
            assertInstanceOf(IOException.class, failure.getCause());
        } finally {
            removeDeletionBlock(argumentFiles);
        }
    }

    @Test
    void cleanupFailureIsSuppressedWhenProcessRunnerAlreadyFailed() throws IOException {
        List<Path> argumentFiles = new ArrayList<>();
        IllegalStateException processFailure = new IllegalStateException("process failed before completion");
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            Path argumentFile = argumentFile(command);
            argumentFiles.add(argumentFile);
            blockDeletion(argumentFile);
            throw processFailure;
        });

        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runner.compile(
                            Path.of("/jdk/bin/java"),
                            Path.of("/jdk"),
                            List.of(Path.of("src/Main.kt")),
                            new Classpath(List.of(Path.of("compiler.jar"))),
                            new Classpath(List.of(Path.of("stdlib.jar"))),
                            tempDir.resolve("process-and-cleanup-failed"),
                            new KotlinCompilerRunner.Options("21", "process_and_cleanup_failed_main", false)));

            assertSame(processFailure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        } finally {
            removeDeletionBlock(argumentFiles);
        }
    }

    @Test
    void keepsLauncherArgvIndependentOfHugeCompilationInputs() {
        String longSegment = "very-long-project-segment-".repeat(6);
        List<Path> sources = IntStream.range(0, 4_000)
                .mapToObj(index -> Path.of("sources", longSegment + index + ".kt"))
                .toList();
        List<Path> compilationEntries = IntStream.range(0, 4_000)
                .mapToObj(index -> Path.of("cache", longSegment + index + ".jar"))
                .toList();
        List<Path> argumentFiles = new ArrayList<>();
        AtomicLong argumentFileSize = new AtomicLong();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            Path argumentFile = argumentFile(command);
            argumentFiles.add(argumentFile);
            argumentFileSize.set(fileSize(argumentFile));
            assertEquals(5, command.size());
            assertTrue(command.stream().mapToInt(String::length).sum() < 512, command.toString());
            assertFalse(command.stream().anyMatch(argument -> argument.contains(longSegment)));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        JavacResult result = runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                sources,
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(compilationEntries),
                tempDir.resolve("huge-classes"),
                new KotlinCompilerRunner.Options("21", "huge_main", false));

        assertEquals(sources.size(), result.sourceCount());
        assertTrue(argumentFileSize.get() > 1_000_000, "expected a response file larger than one megabyte");
        assertFalse(Files.exists(argumentFiles.getFirst()));
    }

    @Test
    void testFailureNamesTheTestScopeAndItsRuntimeDeclarations() {
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command ->
                new KotlinCompilerRunner.ProcessResult(1, "test source failed\n"));

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> runner.compile(
                        Path.of("/jdk/bin/java"),
                        Path.of("/jdk"),
                        List.of(Path.of("src/Test.kt")),
                        new Classpath(List.of(Path.of("compiler.jar"))),
                        new Classpath(List.of(Path.of("stdlib.jar"))),
                        tempDir.resolve("failed-test"),
                        new KotlinCompilerRunner.Options("21", "failed_test", false),
                        KotlinCompilationScope.TEST));

        assertTrue(failure.getMessage().contains("Kotlin test compilation failed"));
        assertTrue(failure.getMessage().contains("[dependencies.test]"));
        assertTrue(failure.getMessage().contains("test source failed"));
    }

    @Test
    void optionsRejectBlankReleaseAndModuleName() {
        assertFalse(new KotlinCompilerRunner.Options("21", "main", false).javaParameters());
        assertThrows(
                KotlinCompileException.class,
                () -> new KotlinCompilerRunner.Options(" ", "main", false));
        assertThrows(
                KotlinCompileException.class,
                () -> new KotlinCompilerRunner.Options("21", " ", false));
    }

    @Test
    void optionsRejectCommaDelimitedFriendPath() {
        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> new KotlinCompilerRunner.Options("21", "demo_test", false)
                        .withFriendPath(Path.of("workspace,copy/target/classes")));

        assertTrue(failure.getMessage().contains("containing a comma"));
    }

    static Path argumentFile(List<String> command) {
        String argument = command.getLast();
        assertTrue(argument.startsWith("@"), command.toString());
        Path path = Path.of(argument.substring(1));
        assertTrue(path.isAbsolute(), path.toString());
        assertTrue(Files.isRegularFile(path), path.toString());
        return path;
    }

    static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void blockDeletion(Path argumentFile) {
        try {
            Files.delete(argumentFile);
            Files.createDirectory(argumentFile);
            Files.writeString(argumentFile.resolve("blocker"), "keep the directory non-empty");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void removeDeletionBlock(List<Path> argumentFiles) throws IOException {
        for (Path argumentFile : argumentFiles) {
            if (Files.isDirectory(argumentFile)) {
                Files.deleteIfExists(argumentFile.resolve("blocker"));
            }
            Files.deleteIfExists(argumentFile);
        }
    }
}
