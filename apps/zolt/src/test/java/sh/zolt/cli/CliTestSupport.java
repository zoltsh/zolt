package sh.zolt.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import picocli.CommandLine;

public final class CliTestSupport {
    private CliTestSupport() {
    }

    public static CommandResult execute(String... args) {
        CommandLine commandLine = newCommandLine();
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));

        int exitCode = commandLine.execute(args);

        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    public static CommandLine newCommandLine() {
        return ZoltCli.newCommandLine();
    }

    /**
     * Runs a directly constructed command object under the CLI's real execution-exception handling, so
     * a command wired with stubbed collaborators reports failures exactly as the assembled CLI does.
     */
    public static CommandResult executeCommand(Object command, String... args) {
        CommandLine commandLine = new CommandLine(command);
        commandLine.setExecutionExceptionHandler(ZoltCli::handleExecutionException);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        commandLine.setOut(new PrintWriter(stdout));
        commandLine.setErr(new PrintWriter(stderr));

        int exitCode = commandLine.execute(args);

        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    public static String memberConfig(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                """.formatted(name, currentJavaMajorVersion());
    }

    public static String generatedSourceConfig(
            String scope,
            String id,
            String output,
            String input,
            boolean required) {
        return """

                [generated.%s.%s]
                kind = "declared-root"
                language = "java"
                output = "%s"
                inputs = ["%s"]
                required = %s
                """.formatted(scope, id, output, input, required);
    }

    public static String sha256(Path path) throws IOException {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static void writeFakeConsoleJar(Path jar) throws IOException {
        Path workDir = jar.getParent().resolve("fake-console-work");
        Path source = workDir.resolve("src/org/junit/platform/console/ConsoleLauncher.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.junit.platform.console;

                public final class ConsoleLauncher {
                    private ConsoleLauncher() {
                    }

                    public static void main(String[] args) throws Exception {
                        System.out.println("fake console");
                        for (int index = 0; index + 1 < args.length; index++) {
                            if ("--details".equals(args[index]) && "tree".equals(args[index + 1])) {
                                System.out.println("fake console event output");
                            }
                        }
                        for (int index = 0; index + 1 < args.length; index++) {
                            if ("--reports-dir".equals(args[index])) {
                                java.nio.file.Path reports = java.nio.file.Path.of(args[index + 1]);
                                java.nio.file.Files.createDirectories(reports);
                                java.nio.file.Files.writeString(
                                        reports.resolve("TEST-fake-console.xml"),
                                        "<testsuite name=\\"fake-console\\" tests=\\"1\\" failures=\\"0\\"></testsuite>\\n");
                            }
                        }
                    }
                }
                """);
        Path classes = workDir.resolve("classes");
        new sh.zolt.build.compile.JavacRunner().compile(
                currentJavac(),
                java.util.List.of(source),
                new sh.zolt.classpath.Classpath(java.util.List.of()),
                classes);

        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("org/junit/platform/console/ConsoleLauncher.class");
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("org/junit/platform/console/ConsoleLauncher.class")));
            output.closeEntry();
        }
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
