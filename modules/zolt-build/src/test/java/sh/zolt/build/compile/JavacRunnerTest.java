package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.AnnotationProcessorFixture;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavacRunnerTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesMainSourcesAndCreatesOutputDirectory() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        Path output = tempDir.resolve("target/classes");

        JavacResult result = new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                output);

        assertEquals(1, result.sourceCount());
        assertEquals(output, result.outputDirectory());
        assertTrue(Files.exists(output.resolve("com/example/Main.class")));
    }

    @Test
    void returnsWithoutRunningJavacWhenThereAreNoSources() {
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        JavacResult result = runner.compile(
                Path.of("javac"),
                List.of(),
                new Classpath(List.of(Path.of("lib.jar"))),
                tempDir.resolve("target/classes"));

        assertEquals(0, result.sourceCount());
        assertTrue(commands.isEmpty());
        assertTrue(Files.exists(tempDir.resolve("target/classes")));
    }

    @Test
    void passesCompileClasspathToJavacCommand() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of(Path.of("zeta.jar"), Path.of("alpha.jar"))),
                tempDir.resolve("target/classes"));

        assertEquals(List.of(
                "javac",
                "-d",
                tempDir.resolve("target/classes").toString(),
                "-classpath",
                "zeta.jar:alpha.jar",
                "-proc:none",
                source.normalize().toString()), commands.getFirst());
    }

    @Test
    void passesProcessorPathAndGeneratedSourcesToJavacCommand() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        Path generatedSources = tempDir.resolve("target/generated/sources/annotations");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of(Path.of("zeta.jar"), Path.of("alpha.jar"))),
                tempDir.resolve("target/classes"),
                new Classpath(List.of(Path.of("processor-z.jar"), Path.of("processor-a.jar"))),
                generatedSources);

        assertEquals(List.of(
                "javac",
                "-d",
                tempDir.resolve("target/classes").toString(),
                "-classpath",
                "zeta.jar:alpha.jar",
                "-processorpath",
                "processor-z.jar:processor-a.jar:zeta.jar:alpha.jar",
                "-s",
                generatedSources.toString(),
                source.normalize().toString()), commands.getFirst());
    }

    @Test
    void passesCompilerOptionsToJavacCommand() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of()),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                new JavacOptions("17", "UTF-8", List.of("-Xlint:deprecation", "-parameters")));

        assertEquals(List.of(
                "javac",
                "-d",
                tempDir.resolve("target/classes").toString(),
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-proc:none",
                "-Xlint:deprecation",
                "-parameters",
                source.normalize().toString()), commands.getFirst());
    }

    @Test
    void routesCompileClasspathOntoModulePathWhenModularAndKeepsItOffClasspath() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of(Path.of("zeta.jar"), Path.of("alpha.jar"))),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                JavacOptions.empty().withModulePath(List.of(Path.of("zeta.jar"), Path.of("alpha.jar"))));

        List<String> command = commands.getFirst();
        assertTrue(command.contains("--module-path"), "modular compile must emit --module-path");
        assertEquals("zeta.jar:alpha.jar", command.get(command.indexOf("--module-path") + 1));
        assertFalse(command.contains("-classpath"), "module dependencies must not also appear on -classpath");
    }

    @Test
    void keepsModuleDependenciesOnClasspathAndAddsModulePathWhenClasspathHasExtraEntries() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of(Path.of("out"), Path.of("mod.jar"))),
                tempDir.resolve("target/classes"),
                new Classpath(List.of()),
                null,
                JavacOptions.empty().withModulePath(List.of(Path.of("mod.jar"))));

        List<String> command = commands.getFirst();
        assertEquals("out", command.get(command.indexOf("-classpath") + 1));
        assertEquals("mod.jar", command.get(command.indexOf("--module-path") + 1));
    }

    @Test
    void nonModularCompileEmitsNoModulePathAndKeepsClasspathByteIdentical() throws IOException {
        Path source = source("src/main/java/com/example/Main.java", "final class Main {}\n");
        List<List<String>> commands = new ArrayList<>();
        JavacRunner runner = new JavacRunner(":", command -> {
            commands.add(command);
            return new JavacRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("javac"),
                List.of(source),
                new Classpath(List.of(Path.of("zeta.jar"), Path.of("alpha.jar"))),
                tempDir.resolve("target/classes"));

        List<String> command = commands.getFirst();
        assertFalse(command.contains("--module-path"), "non-modular compile must not emit --module-path");
        assertEquals(List.of(
                "javac",
                "-d",
                tempDir.resolve("target/classes").toString(),
                "-classpath",
                "zeta.jar:alpha.jar",
                "-proc:none",
                source.normalize().toString()), command);
    }

    @Test
    void modularSourceSetCompilesAgainstExternalNamedModuleThatFailsOnClasspath() throws IOException {
        Path moduleJar = externalNamedModuleJar();
        Path moduleInfo = source("src/main/java/module-info.java", """
                module demo {
                    requires com.example.greet;
                }
                """);
        Path app = source("src/main/java/demo/App.java", """
                package demo;

                import com.example.greet.Greeter;

                public final class App {
                    public static String run() {
                        return Greeter.greet();
                    }
                }
                """);
        Path output = tempDir.resolve("target/classes");

        JavacResult result = new JavacRunner().compile(
                currentJavac(),
                List.of(moduleInfo, app),
                new Classpath(List.of(moduleJar)),
                output,
                new Classpath(List.of()),
                null,
                JavacOptions.empty().withModulePath(List.of(moduleJar)));

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(output.resolve("module-info.class")));
        assertTrue(Files.exists(output.resolve("demo/App.class")));
    }

    @Test
    void modularSourceSetFailsWhenExternalModuleIsOnlyOnClasspath() throws IOException {
        Path moduleJar = externalNamedModuleJar();
        Path moduleInfo = source("src/main/java/module-info.java", """
                module demo {
                    requires com.example.greet;
                }
                """);
        Path app = source("src/main/java/demo/App.java", """
                package demo;

                import com.example.greet.Greeter;

                public final class App {
                    public static String run() {
                        return Greeter.greet();
                    }
                }
                """);
        Path output = tempDir.resolve("target/classes-cp");

        sh.zolt.build.JavacException failure = org.junit.jupiter.api.Assertions.assertThrows(
                sh.zolt.build.JavacException.class,
                () -> new JavacRunner().compile(
                        currentJavac(),
                        List.of(moduleInfo, app),
                        new Classpath(List.of(moduleJar)),
                        output));

        assertTrue(failure.getMessage().contains("module not found"),
                "control: an external module only on -classpath must fail with 'module not found', got: "
                        + failure.getMessage());
    }

    private Path externalNamedModuleJar() throws IOException {
        Path moduleRoot = tempDir.resolve("greet-module");
        Path greetInfo = writeFile(moduleRoot.resolve("src/module-info.java"), """
                module com.example.greet {
                    exports com.example.greet;
                }
                """);
        Path greeter = writeFile(moduleRoot.resolve("src/com/example/greet/Greeter.java"), """
                package com.example.greet;

                public final class Greeter {
                    public static String greet() {
                        return "hi";
                    }
                }
                """);
        Path classes = moduleRoot.resolve("classes");
        try {
            Files.createDirectories(classes);
        } catch (IOException exception) {
            throw exception;
        }
        JavacResult compiled = new JavacRunner().compile(
                currentJavac(),
                List.of(greetInfo, greeter),
                new Classpath(List.of()),
                classes);
        assertEquals(2, compiled.sourceCount());

        Path jar = moduleRoot.resolve("greet.jar");
        List<String> jarCommand = List.of(
                jarExecutable().toString(),
                "--create",
                "--file",
                jar.toString(),
                "-C",
                classes.toString(),
                ".");
        try {
            Process process = new ProcessBuilder(jarCommand).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int code = process.waitFor();
            assertEquals(0, code, "jar packaging failed: " + out);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
        return jar;
    }

    private Path writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static Path jarExecutable() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("jar"));
    }

    @Test
    void runsAnnotationProcessorAndCompilesGeneratedSource() throws IOException {
        Path processorJar = AnnotationProcessorFixture.processorJar(tempDir);
        Path source = source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);
        Path output = tempDir.resolve("target/classes");
        Path generatedSources = tempDir.resolve("target/generated/sources/annotations");

        JavacResult result = new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                output,
                new Classpath(List.of(processorJar)),
                generatedSources);

        assertEquals(1, result.sourceCount());
        assertTrue(Files.exists(generatedSources.resolve("com/example/GeneratedMessage.java")));
        assertTrue(Files.exists(output.resolve("com/example/Main.class")));
        assertTrue(Files.exists(output.resolve("com/example/GeneratedMessage.class")));
    }

    private Path source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
