package sh.zolt.workspace.service;

import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class WorkspaceTestServiceTestSupport {
    private WorkspaceTestServiceTestSupport() {
    }

    static void workspace(Path tempDir, String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    static void member(Path tempDir, String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                %s""".formatted(name, currentJavaMajorVersion(), extraToml));
    }

    static void source(Path tempDir, String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    static void lock(Path tempDir, String content) throws IOException {
        WorkspaceContentAddressedLockTestSupport.write(
                tempDir.resolve("zolt.lock"), tempDir.resolve("cache"), content);
    }

    static void createFakeConsoleJar(Path tempDir, Path jar) throws IOException {
        createFakeConsoleJar(tempDir, jar, "fake console");
    }

    /**
     * Builds a fake {@code ConsoleLauncher} whose {@code main} prints the given output verbatim.
     * Use a JUnit-style summary such as {@code [ 0 tests found ]} to exercise the runner's
     * discovery/false-green guards without downloading a real JUnit Platform.
     */
    static void createFakeConsoleJar(Path tempDir, Path jar, String consoleOutput) throws IOException {
        Path source = tempDir.resolve("fake-console-src/org/junit/platform/console/ConsoleLauncher.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.junit.platform.console;

                public final class ConsoleLauncher {
                    private ConsoleLauncher() {
                    }

                    public static void main(String[] args) {
                        System.out.println(%s);
                    }
                }
                """.formatted(quote(consoleOutput)));
        Path classes = tempDir.resolve("fake-console-classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                classes);

        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("org/junit/platform/console/ConsoleLauncher.class");
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("org/junit/platform/console/ConsoleLauncher.class")));
            output.closeEntry();
        }
    }

    static String zeroTestsFoundSummary() {
        return String.join("\n",
                "Test run finished after 5 ms",
                "[         1 containers found      ]",
                "[         0 tests found           ]",
                "[         0 tests successful      ]",
                "[         0 tests failed          ]");
    }

    static String oneTestSuccessfulSummary() {
        return String.join("\n",
                "Test run finished after 5 ms",
                "[         1 containers found      ]",
                "[         1 tests found           ]",
                "[         1 tests successful      ]",
                "[         0 tests failed          ]");
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }

    static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    static final class CachingJdkChecker implements JdkChecker {
        private int detectCalls;
        private int toolchainReads;
        private JdkStatus status;

        @Override
        public JdkStatus detect(String requiredVersion) {
            detectCalls++;
            if (status == null) {
                toolchainReads++;
                Path javaHome = Path.of(System.getProperty("java.home"));
                status = new JdkStatus(
                        Optional.of(javaHome),
                        Optional.of(javaHome.resolve("bin").resolve(executable("java"))),
                        Optional.of(javaHome.resolve("bin").resolve(executable("javac"))),
                        Optional.of(javaHome.resolve("bin").resolve(executable("jar"))),
                        Optional.of(requiredVersion),
                        requiredVersion);
            }
            return status;
        }

        int detectCalls() {
            return detectCalls;
        }

        int toolchainReads() {
            return toolchainReads;
        }
    }
}
