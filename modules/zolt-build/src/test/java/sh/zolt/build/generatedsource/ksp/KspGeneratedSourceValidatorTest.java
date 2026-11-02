package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspGeneratedSourceValidatorTest {
    @TempDir
    private Path projectDirectory;

    @Test
    void returnsTheValidatedOwnedLayout() {
        KspOutputLayout layout = KspGeneratedSourceValidator.validate(
                root(), "main", step("kotlin", List.of(), true));

        assertEquals(
                root().resolve("target/generated/ksp/main/symbols"),
                layout.baseDirectory());
    }

    @Test
    void rejectsLanguageInputsAndPreservedOutput() {
        assertInvalid(step("java", List.of(), true), "internal Kotlin source lane");
        assertInvalid(step("kotlin", List.of("src/main/kotlin"), true), "must not declare inputs");
        assertInvalid(step("kotlin", List.of(), false), "must clean its owned output");
    }

    @Test
    void rejectsSettingsOwnedByAnotherGenerator() {
        GeneratedSourceStep mixed = new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                "kotlin",
                "target/generated/ksp/main/symbols",
                List.of(),
                true,
                true,
                new OpenApiGenerationSettings(
                        Optional.of("org.openapitools:openapi-generator-cli"),
                        Optional.of("7.11.0"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("java"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                ksp());

        assertInvalid(mixed, "settings owned by a different generated source kind");
    }

    @Test
    void rejectsAnOwnedLaneThatEscapesThroughASymlink() throws IOException {
        Path base = projectDirectory.resolve("target/generated/ksp/main/symbols");
        Files.createDirectories(base);
        Path outside = Files.createTempDirectory(projectDirectory.getParent(), "outside-ksp-java-");
        createSymlink(base.resolve("java"), outside);

        BuildException exception = assertThrows(
                BuildException.class,
                () -> KspGeneratedSourceValidator.validate(
                        root(), "main", step("kotlin", List.of(), true)));

        assertTrue(exception.getMessage().contains("Invalid KSP owned java path"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void rejectsADanglingOwnedLaneSymlink() throws IOException {
        Path base = projectDirectory.resolve("target/generated/ksp/main/symbols");
        Files.createDirectories(base);
        createSymlink(
                base.resolve("resources"),
                projectDirectory.getParent().resolve("missing-ksp-resources"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> KspGeneratedSourceValidator.validate(
                        root(), "main", step("kotlin", List.of(), true)));

        assertTrue(exception.getMessage().contains("Invalid KSP owned resources path"));
        assertTrue(exception.getMessage().contains("Could not validate"));
    }

    private void assertInvalid(GeneratedSourceStep step, String fragment) {
        BuildException exception = assertThrows(
                BuildException.class,
                () -> KspGeneratedSourceValidator.validate(root(), "main", step));
        assertTrue(exception.getMessage().contains(fragment));
    }

    private GeneratedSourceStep step(String language, List<String> inputs, boolean clean) {
        return new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                language,
                "target/generated/ksp/main/symbols",
                inputs,
                true,
                clean,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                ksp());
    }

    private static KspGenerationSettings ksp() {
        return new KspGenerationSettings(
                "ksp",
                Optional.of("2.2.0-2.0.2"),
                Optional.empty(),
                List.of(new KspProcessorSettings(
                        "com.example:processor", "1.0.0", Optional.empty())),
                Map.of());
    }

    private Path root() {
        return projectDirectory.toAbsolutePath().normalize();
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
