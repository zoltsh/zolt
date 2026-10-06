package sh.zolt.cli.build.fixture;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Locates exact test-runtime artifacts and runs fixture compilation outside the JUnit classloader. */
public final class KotlinFixtureCompiler {
    private static final long TIMEOUT_SECONDS = 60L;

    private KotlinFixtureCompiler() {
    }

    public static Path runtimeJar(String artifactId, String preferredVersion) {
        String exactName = artifactId + "-" + preferredVersion + ".jar";
        List<Path> candidates = runtimeClasspath().stream()
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith(artifactId + "-"))
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .toList();
        return candidates.stream()
                .filter(path -> path.getFileName().toString().equals(exactName))
                .findFirst()
                .orElseGet(() -> {
                    if (candidates.size() == 1) {
                        return candidates.getFirst();
                    }
                    throw new IllegalStateException(
                            "Expected one test-runtime JAR for " + artifactId + " but found " + candidates);
                });
    }

    public static void compile(String subject, List<String> arguments)
            throws IOException, InterruptedException {
        List<Path> classpath = runtimeClasspath().stream()
                .filter(path -> !path.getFileName().toString().startsWith("symbol-processing-"))
                .toList();
        if (classpath.stream().noneMatch(path -> path.getFileName().toString()
                .startsWith("kotlin-compiler-embeddable-"))) {
            throw new IllegalStateException("Kotlin fixture compiler is absent from the test runtime.");
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(String.join(
                File.pathSeparator,
                classpath.stream().map(Path::toString).toList()));
        command.add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(subject + " timed out after " + TIMEOUT_SECONDS + " seconds.");
        }
        String diagnostics = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .strip();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Could not compile the " + subject + ": " + diagnostics);
        }
    }

    private static List<Path> runtimeClasspath() {
        return Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path", ""))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    private static Path javaExecutable() {
        String name = System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }
}
