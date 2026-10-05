package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.incremental.IncrementalCompilePlanner;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class MainCompileSourceExecutorKotlinTest {
    @TempDir
    private Path projectDir;

    @Test
    void skippedKotlinCompileCountsSourcesWithoutLaunchingCompiler() {
        KotlinCompilerRunner runner = runnerThatMustNotRun();
        SourceDiscoveryResult sources = sources(
                List.of(),
                List.of(projectDir.resolve("src/main/java/com/example/Main.kt")));

        MainCompileSourceExecutor.Attempt result = executor(runner).compile(
                true,
                "fingerprint-match",
                projectDir,
                config(),
                sources,
                classpaths(List.of(), List.of()),
                projectDir.resolve("target/classes"),
                projectDir.resolve("target/generated/sources/annotations"),
                jdkStatus(),
                selection());

        assertEquals(1, result.sourceCount());
        assertEquals("skipped", result.mode());
        assertEquals(List.of(), result.compiledSources());
    }

    @Test
    void compilesKotlinWithIsolatedToolchainAfterResettingOwnedOutputs() throws IOException {
        Path first = source("src/main/java/com/example/Zed.kt", "class Zed");
        Path second = source("src/main/java/com/example/Alpha.kt", "class Alpha");
        Path output = projectDir.resolve("target/classes");
        Path generated = projectDir.resolve("target/generated/sources/annotations");
        Path staleClass = output.resolve("com/example/Stale.class");
        Path staleState = output.resolve(".zolt-incremental-main.state");
        Path staleGenerated = generated.resolve("com/example/Stale.java");
        Files.createDirectories(staleClass.getParent());
        Files.createDirectories(staleGenerated.getParent());
        Files.write(staleClass, new byte[] {1});
        Files.writeString(staleState, "stale");
        Files.writeString(staleGenerated, "class Stale {}");
        List<List<String>> commands = new ArrayList<>();
        List<Path> argumentFiles = new ArrayList<>();
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            assertFalse(Files.exists(staleClass));
            assertFalse(Files.exists(staleState));
            assertFalse(Files.exists(staleGenerated));
            commands.add(command);
            Path argumentFile = argumentFile(command);
            argumentFiles.add(argumentFile);
            argumentContents.add(readString(argumentFile));
            return new KotlinCompilerRunner.ProcessResult(0, "compiled kotlin\n");
        });
        SourceDiscoveryResult sources = sources(List.of(), List.of(first, second));
        Path applicationJar = projectDir.resolve("cache/application.jar");

        MainCompileSourceExecutor.Attempt result = executor(runner).compile(
                false,
                "source-changed",
                projectDir,
                config(),
                sources,
                classpaths(List.of(applicationJar), List.of()),
                output,
                generated,
                jdkStatus(),
                selection());

        assertEquals(2, result.sourceCount());
        assertEquals("full", result.mode());
        assertEquals("kotlin-main-sources", result.fallbackReason());
        assertEquals(2, result.diagnostics().sourcesRecompiled());
        assertFalse(result.attribution().present());
        assertEquals(sources.allMainSources(), result.compiledSources());
        assertEquals("compiled kotlin\n", result.output());
        List<String> command = commands.getFirst();
        assertEquals("/managed-jdk/bin/java", command.getFirst());
        assertEquals(
                launcherEntries().stream().map(Path::toString).reduce((left, right) -> left + ":" + right).orElseThrow(),
                command.get(command.indexOf("-cp") + 1));
        assertEquals(5, command.size());
        assertEquals("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", command.get(3));
        assertEquals("@" + argumentFiles.getFirst(), command.getLast());
        assertEquals(KotlinCompilerArgumentsFile.encode(List.of(
                "-no-stdlib",
                "-no-reflect",
                "-jdk-home",
                "/managed-jdk",
                "-Xjdk-release=21",
                "-classpath",
                applicationJar.toString(),
                "-module-name",
                "demo_main",
                "-d",
                output.toString(),
                second.toString(),
                first.toString())), argumentContents.getFirst());
        assertFalse(Files.exists(argumentFiles.getFirst()));
    }

    @Test
    void rejectsMissingToolchainBeforeMutatingOutput() throws IOException {
        Path output = outputWithStaleClass();
        Path kotlin = source("src/main/java/com/example/Main.kt", "class Main");

        KotlinCompileException exception = assertThrows(
                KotlinCompileException.class,
                () -> executor(runnerThatMustNotRun()).compile(
                        false,
                        projectDir,
                        config(),
                        sources(List.of(), List.of(kotlin)),
                        classpaths(List.of(), List.of()),
                        output,
                        projectDir.resolve("target/generated/sources/annotations"),
                        jdkStatus()));

        assertTrue(exception.getMessage().contains("checksum-verified compiler toolchain"));
        assertTrue(Files.exists(output.resolve("com/example/StillHere.class")));
    }

    @Test
    void compilesMixedJavaAndKotlinInTwoPhasesWithoutDoubleCountingSources() throws IOException {
        Path output = outputWithStaleClass();
        Path java = source("src/main/java/com/example/JavaApi.java", "class JavaApi {}");
        Path kotlin = source("src/main/java/com/example/Main.kt", "class Main");
        Path applicationJar = projectDir.resolve("cache/application.jar");
        Path kotlinMarker = output.resolve("com/example/Main.class");
        List<String> phases = new ArrayList<>();
        List<List<String>> kotlinCommands = new ArrayList<>();
        List<Path> kotlinArgumentFiles = new ArrayList<>();
        List<String> kotlinArgumentContents = new ArrayList<>();
        List<List<String>> javacCommands = new ArrayList<>();
        KotlinCompilerRunner kotlinRunner = new KotlinCompilerRunner(":", command -> {
            assertFalse(Files.exists(output.resolve("com/example/StillHere.class")));
            phases.add("kotlin");
            kotlinCommands.add(command);
            Path argumentFile = argumentFile(command);
            kotlinArgumentFiles.add(argumentFile);
            kotlinArgumentContents.add(readString(argumentFile));
            try {
                Files.createDirectories(kotlinMarker.getParent());
                Files.write(kotlinMarker, new byte[] {1});
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new KotlinCompilerRunner.ProcessResult(0, "compiled kotlin\n");
        });
        JavacRunner javacRunner = new JavacRunner(":", command -> {
            assertTrue(Files.exists(kotlinMarker), "javac must see the Kotlin phase output");
            phases.add("javac");
            javacCommands.add(command);
            return new JavacRunner.ProcessResult(0, "compiled java\n");
        });
        SourceDiscoveryResult sources = sources(List.of(java), List.of(kotlin));

        MainCompileSourceExecutor.Attempt result = executor(javacRunner, kotlinRunner).compile(
                false,
                "source-changed",
                projectDir,
                config(),
                sources,
                classpaths(List.of(applicationJar), List.of()),
                output,
                projectDir.resolve("target/generated/sources/annotations"),
                jdkStatus(),
                selection());

        assertEquals(List.of("kotlin", "javac"), phases);
        assertEquals(2, result.sourceCount());
        assertEquals(2, result.diagnostics().sourcesRecompiled());
        assertEquals(sources.allMainSources(), result.compiledSources());
        assertEquals("compiled kotlin\ncompiled java\n", result.output());
        List<String> kotlinCommand = kotlinCommands.getFirst();
        assertEquals(5, kotlinCommand.size());
        assertEquals(
                launcherEntries().stream().map(Path::toString).reduce((left, right) -> left + ":" + right).orElseThrow(),
                kotlinCommand.get(kotlinCommand.indexOf("-cp") + 1));
        assertEquals("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", kotlinCommand.get(3));
        assertEquals("@" + kotlinArgumentFiles.getFirst(), kotlinCommand.getLast());
        assertEquals(KotlinCompilerArgumentsFile.encode(List.of(
                "-no-stdlib",
                "-no-reflect",
                "-jdk-home",
                "/managed-jdk",
                "-Xjdk-release=21",
                "-classpath",
                applicationJar.toString(),
                "-module-name",
                "demo_main",
                "-d",
                output.toString(),
                java.toString(),
                kotlin.toString())), kotlinArgumentContents.getFirst());
        assertFalse(Files.exists(kotlinArgumentFiles.getFirst()));
        List<String> javacCommand = javacCommands.getFirst();
        assertTrue(javacCommand.contains(java.toString()), javacCommand.toString());
        assertFalse(javacCommand.contains(kotlin.toString()), javacCommand.toString());
        assertEquals(
                output + ":" + applicationJar,
                javacCommand.get(javacCommand.indexOf("-classpath") + 1));
        assertEquals("21", javacCommand.get(javacCommand.indexOf("--release") + 1));
        assertEquals("UTF-8", javacCommand.get(javacCommand.indexOf("-encoding") + 1));
        assertTrue(javacCommand.contains("-proc:none"), javacCommand.toString());
    }

    @Test
    void runsKaptThenKotlinThenJavacWithoutDoubleProcessing() throws IOException {
        Path output = outputWithStaleClass();
        Path generated = projectDir.resolve("target/generated/sources/annotations");
        Path java = source("src/main/java/com/example/JavaApi.java", "class JavaApi {}");
        Path kotlin = source("src/main/kotlin/com/example/Main.kt", "class Main");
        Path processor = projectDir.resolve("cache/processor.jar").toAbsolutePath().normalize();
        Path generatedJava = generated.resolve("com/example/GeneratedGreeting.java");
        Path kotlinMarker = output.resolve("com/example/Main.class");
        List<String> phases = new ArrayList<>();
        List<Path> stubsDirectories = new ArrayList<>();
        KotlinCompilerRunner kotlinRunner = new KotlinCompilerRunner(":", command -> {
            String arguments = readString(argumentFile(command));
            if (arguments.contains("org.jetbrains.kotlin.kapt3:aptMode=stubsAndApt")) {
                phases.add("kapt");
                assertTrue(arguments.contains("org.jetbrains.kotlin.kapt3:apclasspath=" + processor));
                assertTrue(arguments.contains("org.jetbrains.kotlin.kapt3:includeCompileClasspath=false"));
                assertFalse(Files.exists(output.resolve("com/example/StillHere.class")));
                Path stubs = pluginPath(arguments, "stubs");
                stubsDirectories.add(stubs);
                try {
                    Files.writeString(stubs.resolve("Main.java"), "class Main {}");
                    Files.createDirectories(generatedJava.getParent());
                    Files.writeString(generatedJava, "package com.example; class GeneratedGreeting {}");
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                return new KotlinCompilerRunner.ProcessResult(0, "generated kapt\n");
            }
            phases.add("kotlin");
            assertFalse(arguments.contains("org.jetbrains.kotlin.kapt3"));
            assertTrue(arguments.contains(generatedJava.toString()), arguments);
            try {
                Files.createDirectories(kotlinMarker.getParent());
                Files.write(kotlinMarker, new byte[] {1});
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new KotlinCompilerRunner.ProcessResult(0, "compiled kotlin\n");
        });
        JavacRunner javacRunner = new JavacRunner(":", command -> {
            phases.add("javac");
            assertTrue(Files.exists(kotlinMarker));
            assertTrue(command.contains(java.toString()), command.toString());
            assertTrue(command.contains(generatedJava.toString()), command.toString());
            assertTrue(command.contains("-proc:none"), command.toString());
            assertFalse(command.contains("-processorpath"), command.toString());
            assertFalse(command.stream().anyMatch(argument -> argument.contains("processor.jar")));
            return new JavacRunner.ProcessResult(0, "compiled java\n");
        });

        MainCompileSourceExecutor.Attempt result = executor(javacRunner, kotlinRunner).compile(
                false,
                "source-changed",
                projectDir,
                config(),
                sources(List.of(java), List.of(kotlin)),
                classpaths(List.of(), List.of(processor)),
                output,
                generated,
                jdkStatus(),
                selectionWithKapt());

        assertEquals(List.of("kapt", "kotlin", "javac"), phases);
        assertEquals(2, result.sourceCount());
        assertEquals(2, result.diagnostics().sourcesRecompiled());
        assertEquals("generated kapt\ncompiled kotlin\ncompiled java\n", result.output());
        assertTrue(Files.isRegularFile(generatedJava));
        assertEquals(1, stubsDirectories.size());
        assertFalse(Files.exists(stubsDirectories.getFirst()));
    }

    @Test
    void removesKaptStubsWhenAnnotationProcessingFails() throws IOException {
        Path kotlin = source("src/main/kotlin/com/example/Main.kt", "class Main");
        Path processor = projectDir.resolve("cache/processor.jar").toAbsolutePath().normalize();
        List<Path> stubsDirectories = new ArrayList<>();
        KotlinCompilerRunner failing = new KotlinCompilerRunner(":", command -> {
            String arguments = readString(argumentFile(command));
            Path stubs = pluginPath(arguments, "stubs");
            stubsDirectories.add(stubs);
            try {
                Files.writeString(stubs.resolve("Main.java"), "class Main {}");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return new KotlinCompilerRunner.ProcessResult(1, "processor failed\n");
        });

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> executor(failing).compile(
                        false,
                        "source-changed",
                        projectDir,
                        config(),
                        sources(List.of(), List.of(kotlin)),
                        classpaths(List.of(), List.of(processor)),
                        projectDir.resolve("target/classes"),
                        projectDir.resolve("target/generated/sources/annotations"),
                        jdkStatus(),
                        selectionWithKapt()));

        assertTrue(failure.getMessage().contains("processor failed"));
        assertEquals(1, stubsDirectories.size());
        assertFalse(Files.exists(stubsDirectories.getFirst()));
    }

    @Test
    void rejectsProcessorsWithoutVerifiedKaptBeforeMutatingOutput() throws IOException {
        Path output = outputWithStaleClass();
        Path kotlin = source("src/main/java/com/example/Main.kt", "class Main");

        KotlinCompileException exception = assertThrows(
                KotlinCompileException.class,
                () -> executor(runnerThatMustNotRun()).compile(
                        false,
                        "source-changed",
                        projectDir,
                        config(),
                        sources(List.of(), List.of(kotlin)),
                        classpaths(List.of(), List.of(projectDir.resolve("cache/processor.jar"))),
                        output,
                        projectDir.resolve("target/generated/sources/annotations"),
                        jdkStatus(),
                        selection()));

        assertTrue(exception.getMessage().contains("kotlin-annotation-processing-embeddable"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
        assertTrue(Files.exists(output.resolve("com/example/StillHere.class")));
    }

    @Test
    void kotlinFailurePreservesDiagnosticsAndDoesNotLaunchJavac() throws IOException {
        Path java = source("src/main/java/com/example/JavaApi.java", "class JavaApi {}");
        Path kotlin = source("src/main/java/com/example/Main.kt", "class Main");
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command ->
                new KotlinCompilerRunner.ProcessResult(2, "compiler diagnostics\n"));
        JavacRunner javac = new JavacRunner(":", command -> {
            throw new AssertionError("javac must not run after a failed Kotlin phase");
        });

        KotlinCompileException exception = assertThrows(
                KotlinCompileException.class,
                () -> executor(javac, runner).compile(
                        false,
                        "source-changed",
                        projectDir,
                        config(),
                        sources(List.of(java), List.of(kotlin)),
                        classpaths(List.of(), List.of()),
                        projectDir.resolve("target/classes"),
                        projectDir.resolve("target/generated/sources/annotations"),
                        jdkStatus(),
                        selection()));

        assertTrue(exception.getMessage().contains("exit code 2"));
        assertTrue(exception.getMessage().contains("compiler diagnostics"));
    }

    private MainCompileSourceExecutor executor(KotlinCompilerRunner runner) {
        return executor(new JavacRunner(), runner);
    }

    private MainCompileSourceExecutor executor(
            JavacRunner javacRunner,
            KotlinCompilerRunner runner) {
        return new MainCompileSourceExecutor(
                javacRunner,
                new GroovyCompilerRunner(),
                runner,
                new IncrementalCompileStateRecorder(),
                new IncrementalCompilePlanner());
    }

    private KotlinCompilerRunner runnerThatMustNotRun() {
        return new KotlinCompilerRunner(":", command -> {
            throw new AssertionError("Kotlin compiler must not run");
        });
    }

    private MainCompilerToolchain selection() {
        return MainCompilerToolchain.kotlin(new KotlinCompilerToolchain(
                "2.2.0",
                "a".repeat(64),
                launcherEntries(),
                "sha256:" + "b".repeat(64)));
    }

    private MainCompilerToolchain selectionWithKapt() {
        Path kapt = projectDir.resolve("verified/kotlin-annotation-processing-embeddable.jar")
                .toAbsolutePath()
                .normalize();
        List<Path> launcher = new ArrayList<>(launcherEntries());
        launcher.add(kapt);
        return MainCompilerToolchain.kotlin(new KotlinCompilerToolchain(
                "2.2.0",
                "a".repeat(64),
                launcher,
                "sha256:" + "c".repeat(64),
                kapt));
    }

    private List<Path> launcherEntries() {
        return List.of(
                projectDir.resolve("verified/kotlin-compiler-embeddable.jar").toAbsolutePath().normalize(),
                projectDir.resolve("verified/kotlin-daemon-embeddable.jar").toAbsolutePath().normalize());
    }

    private Path outputWithStaleClass() throws IOException {
        Path output = projectDir.resolve("target/classes");
        Path stale = output.resolve("com/example/StillHere.class");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[] {1});
        return output;
    }

    private Path source(String relativePath, String content) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static Path argumentFile(List<String> command) {
        String argument = command.getLast();
        assertTrue(argument.startsWith("@"), command.toString());
        Path path = Path.of(argument.substring(1));
        assertTrue(path.isAbsolute(), path.toString());
        assertTrue(Files.isRegularFile(path), path.toString());
        return path;
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path pluginPath(String arguments, String name) {
        String prefix = "\"plugin:org.jetbrains.kotlin.kapt3:" + name + "=";
        int start = arguments.indexOf(prefix);
        assertTrue(start >= 0, arguments);
        int valueStart = start + prefix.length();
        int end = arguments.indexOf('"', valueStart);
        assertTrue(end > valueStart, arguments);
        return Path.of(arguments.substring(valueStart, end));
    }

    private static SourceDiscoveryResult sources(List<Path> java, List<Path> kotlin) {
        return new SourceDiscoveryResult(java, List.of(), kotlin, List.of(), List.of(), List.of());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ClasspathSet classpaths(List<Path> compileEntries, List<Path> processorEntries) {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(
                new Classpath(compileEntries),
                empty,
                empty,
                empty,
                new Classpath(processorEntries),
                empty,
                empty);
    }

    private static JdkStatus jdkStatus() {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
    }
}
