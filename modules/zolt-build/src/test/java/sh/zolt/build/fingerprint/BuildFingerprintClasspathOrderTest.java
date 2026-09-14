package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.JavacException;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildFingerprintClasspathOrderTest {
    @TempDir
    private Path projectDir;

    @Test
    void classpathPrecedenceChangesCompilerResultAndFingerprints() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Path stringApi = compileApi(
                "string-api",
                "public static final String VALUE = \"string\";");
        Path integerApi = compileApi(
                "integer-api",
                "public static final int VALUE = 1;");
        Path consumer = write(
                "src/main/java/consumer/Consumer.java",
                "package consumer; public class Consumer { String value = duplicate.Api.VALUE; }\n");

        new JavacRunner().compile(
                currentJavac(),
                List.of(consumer),
                new Classpath(List.of(stringApi, integerApi)),
                projectDir.resolve("consumer/string-first"));
        assertThrows(
                JavacException.class,
                () -> new JavacRunner().compile(
                        currentJavac(),
                        List.of(consumer),
                        new Classpath(List.of(integerApi, stringApi)),
                        projectDir.resolve("consumer/integer-first")));

        assertNotEquals(
                fingerprint(List.of(stringApi, integerApi), false),
                fingerprint(List.of(integerApi, stringApi), false));
        assertNotEquals(
                fingerprint(List.of(stringApi, integerApi), true),
                fingerprint(List.of(integerApi, stringApi), true));
        assertNotEquals(
                fingerprint(List.of(), List.of(stringApi, integerApi), false),
                fingerprint(List.of(), List.of(integerApi, stringApi), false));
        assertNotEquals(
                fingerprint(List.of(), List.of(stringApi, integerApi), true),
                fingerprint(List.of(), List.of(integerApi, stringApi), true));
    }

    private Path compileApi(String directory, String member) throws IOException {
        Path source = write(
                directory + "/src/duplicate/Api.java",
                "package duplicate; public class Api { " + member + " }\n");
        Path output = projectDir.resolve(directory).resolve("classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                output);
        return output;
    }

    private String fingerprint(List<Path> classpath, boolean cacheKeyMode) {
        return fingerprint(classpath, List.of(), cacheKeyMode);
    }

    private String fingerprint(List<Path> classpath, List<Path> processorClasspath, boolean cacheKeyMode) {
        return new BuildFingerprintContent().fingerprint(
                projectDir,
                config(),
                projectDir.resolve("zolt.lock"),
                List.of("src/main/java"),
                List.of(),
                "[resources].main",
                List.of(),
                List.of(),
                List.of(),
                new Classpath(classpath),
                new Classpath(processorClasspath),
                projectDir.resolve("target/classes"),
                "target/classes",
                projectDir.resolve("target/generated/sources/annotations"),
                null,
                null,
                cacheKeyMode);
    }

    private Path write(String relative, String content) throws IOException {
        Path path = projectDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
