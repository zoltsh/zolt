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

final class KspOutputLayoutTest {
    @TempDir
    private Path projectDirectory;

    @Test
    void resolvesEveryOwnedLaneUnderOneValidatedBase() {
        KspOutputLayout layout = KspOutputLayout.resolve(
                root(), "main", step("target/generated/ksp/main/symbols"));
        Path base = root().resolve("target/generated/ksp/main/symbols");

        assertEquals(base, layout.baseDirectory());
        assertEquals(base.resolve("cache"), layout.cachesDirectory());
        assertEquals(base.resolve("classes"), layout.classOutputDirectory());
        assertEquals(base.resolve("kotlin"), layout.kotlinOutputDirectory());
        assertEquals(base.resolve("java"), layout.javaOutputDirectory());
        assertEquals(base.resolve("resources"), layout.resourceOutputDirectory());
    }

    @Test
    void rejectsOutputOutsideTheProject() {
        BuildException exception = assertThrows(
                BuildException.class,
                () -> KspOutputLayout.resolve(root(), "test", step("../outside")));

        assertTrue(exception.getMessage().contains("Invalid KSP output path `../outside`"));
        assertTrue(exception.getMessage().contains("[generated.test.symbols].output"));
    }

    @Test
    void rejectsOutputThroughAnEscapingSymlink() throws IOException {
        Path outside = Files.createTempDirectory(projectDirectory.getParent(), "outside-ksp-");
        createSymlink(projectDirectory.resolve("target"), outside);

        BuildException exception = assertThrows(
                BuildException.class,
                () -> KspOutputLayout.resolve(
                        root(), "main", step("target/generated/ksp/main/symbols")));

        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void rejectsNonKspStepsAndUnknownScopes() {
        GeneratedSourceStep declared = new GeneratedSourceStep(
                "declared",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated",
                List.of(),
                true,
                false);

        assertThrows(BuildException.class, () -> KspOutputLayout.resolve(root(), "main", declared));
        assertThrows(BuildException.class, () -> KspOutputLayout.resolve(root(), "integration", step("target/ksp")));
    }

    private GeneratedSourceStep step(String output) {
        return new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                "kotlin",
                output,
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of()));
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
