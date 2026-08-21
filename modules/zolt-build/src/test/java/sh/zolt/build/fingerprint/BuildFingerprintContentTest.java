package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildFingerprintContentTest {
    private final BuildFingerprintContent content = new BuildFingerprintContent();

    @TempDir
    private Path projectDir;

    @Test
    void fingerprintContentIsDeterministicAndFiltersResourceOutputs() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Path alpha = write("src/main/java/com/example/Alpha.java", "class Alpha {}\n");
        Path beta = write("src/main/java/com/example/Beta.java", "class Beta {}\n");
        write("target/classes/com/example/Alpha.class", "compiled");
        write("target/classes/com/example/Beta.class", "compiled");
        write("src/main/resources/application.properties", "name=demo\n");
        write("src/main/resources/Ignored.java", "class Ignored {}\n");
        write("src/main/resources/target/generated.txt", "ignored\n");
        write("api/openapi.yaml", "openapi: 3.0.0\n");
        write("target/generated/sources/annotations/com/example/Generated.java", "class Generated {}\n");
        ProjectConfig config = config(List.of(generatedStep("api/openapi.yaml")));

        String first = fingerprint(config, List.of(beta, alpha));
        String second = fingerprint(config, List.of(alpha, beta));

        assertEquals(first, second);
        assertTrue(first.contains("[resources]\n"));
        assertTrue(first.contains("src/main/resources/application.properties|"));
        assertTrue(first.contains("[generatedSourceInputs]\napi/openapi.yaml|"));
        assertTrue(first.contains("[generatedSources]\ntarget/generated/sources/annotations/com/example/Generated.java|"));
        assertTrue(first.contains("[expectedClasses]\ntarget/classes/com/example/Alpha.class\n"));
        assertTrue(first.contains("target/classes/com/example/Beta.class\n"));
        assertFalse(first.contains("src/main/resources/Ignored.java|"));
        assertFalse(first.contains("src/main/resources/target/generated.txt|"));
    }

    @Test
    void generatedSourceInputEscapeIsActionable() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = config(List.of(generatedStep("../api.yaml")));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> fingerprint(config, List.of()));

        assertTrue(exception.getMessage().contains("[generated.openapi].inputs"));
        assertTrue(exception.getMessage().contains("../api.yaml"));
    }

    @Test
    void resourceRootEscapeIsActionable() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = config(List.of()).withBuildSettings(new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of(),
                List.of("../resources"),
                List.of("src/test/resources"),
                null));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> fingerprint(config, List.of()));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("../resources"));
    }

    @Test
    void fingerprintIgnoresDependencyDeclarationOrderButTracksProjectJava() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Map<String, String> firstDependencies = new LinkedHashMap<>();
        firstDependencies.put("com.example:first", "1.0.0");
        firstDependencies.put("com.example:second", "2.0.0");
        Map<String, String> reversedDependencies = new LinkedHashMap<>();
        reversedDependencies.put("com.example:second", "2.0.0");
        reversedDependencies.put("com.example:first", "1.0.0");

        String first = fingerprint(config("21", firstDependencies), List.of());
        String reversed = fingerprint(config("21", reversedDependencies), List.of());
        String differentJava = fingerprint(config("17", firstDependencies), List.of());

        assertEquals(first, reversed);
        assertNotEquals(first, differentJava);
    }

    private String fingerprint(ProjectConfig config, List<Path> sources) {
        return content.fingerprint(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                List.of("src/main/java"),
                config.build().resourceRoots(),
                "[resources].main",
                sources,
                config.build().generatedMainSources(),
                List.of(),
                new Classpath(List.of()),
                new Classpath(List.of()),
                projectDir.resolve("target/classes"),
                config.build().output(),
                projectDir.resolve("target/generated/sources/annotations"),
                null,
                null);
    }

    private Path write(String relativePath, String text) throws IOException {
        Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }

    private static GeneratedSourceStep generatedStep(String input) {
        return new GeneratedSourceStep(
                "openapi",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated/sources/openapi",
                List.of(input),
                true,
                false);
    }

    private static ProjectConfig config(List<GeneratedSourceStep> generatedMainSources) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults().withGeneratedSources(generatedMainSources, List.of()));
    }

    private static ProjectConfig config(String java, Map<String, String> dependencies) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", java, Optional.empty()),
                ProjectConfig.defaultRepositories(),
                dependencies,
                Map.of(),
                BuildSettings.defaults());
    }
}
