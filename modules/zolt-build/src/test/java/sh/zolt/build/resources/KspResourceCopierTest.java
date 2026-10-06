package sh.zolt.build.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import sh.zolt.build.ResourceCopyException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspResourceCopierTest extends ResourceCopierTestSupport {
    private final ResourceCopier copier = new ResourceCopier();

    @Test
    void copiesOnlyTheOwnedKspResourceLane() throws IOException {
        Path resource = resource(
                "target/generated/ksp/main/symbols/resources/META-INF/services/demo.Provider",
                "demo.GeneratedProvider\n");
        resource("target/generated/ksp/main/symbols/kotlin/demo/Generated.kt", "class Generated\n");
        resource("target/generated/ksp/main/symbols/java/demo/Generated.java", "class Generated {}\n");
        resource("target/generated/ksp/main/symbols/classes/demo/Generated.class", "class-bytes\n");

        ResourceCopyResult result = copier.copyMainResources(
                projectDir,
                settings(List.of(step("main", "target/generated/ksp/main/symbols")), List.of()));

        Path copied = projectDir.resolve("target/classes/META-INF/services/demo.Provider");
        assertEquals(List.of(resource), result.copiedResources());
        assertEquals("demo.GeneratedProvider\n", Files.readString(copied));
        assertFalse(Files.exists(projectDir.resolve("target/classes/demo/Generated.kt")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/demo/Generated.java")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/demo/Generated.class")));
    }

    @Test
    void copiesTheTestKspResourceLaneToTestClasses() throws IOException {
        Path resource = resource(
                "target/generated/ksp/test/symbols/resources/fixture.properties",
                "fixture=true\n");

        ResourceCopyResult result = copier.copyTestResources(
                projectDir,
                settings(List.of(), List.of(step("test", "target/generated/ksp/test/symbols"))));

        assertEquals(List.of(resource), result.copiedResources());
        assertEquals(
                "fixture=true\n",
                Files.readString(projectDir.resolve("target/test-classes/fixture.properties")));
    }

    @Test
    void rejectsConflictsWithAuthoredResources() throws IOException {
        resource("src/main/resources/application.properties", "authored=true\n");
        resource(
                "target/generated/ksp/main/symbols/resources/application.properties",
                "generated=true\n");

        ResourceCopyException failure = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(
                        projectDir,
                        settings(List.of(step("main", "target/generated/ksp/main/symbols")), List.of())));

        assertTrue(failure.getMessage().contains("Duplicate resource path `application.properties`"));
        assertTrue(failure.getMessage().contains("KSP step [generated.main.main]"));
        assertEquals(
                "authored=true\n",
                Files.readString(projectDir.resolve("target/classes/application.properties")));
    }

    @Test
    void rejectsAResourceSymlinkOutsideTheProject() throws IOException {
        Path outside = Files.createTempFile(projectDir.getParent(), "outside-ksp-resource-", ".txt");
        Files.writeString(outside, "secret\n");
        Path link = projectDir.resolve(
                "target/generated/ksp/main/symbols/resources/secret.txt");
        Files.createDirectories(link.getParent());
        createSymlink(link, outside);

        ResourceCopyException failure = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(
                        projectDir,
                        settings(List.of(step("main", "target/generated/ksp/main/symbols")), List.of())));

        assertTrue(failure.getMessage().contains("resolved through symlinks"));
        assertFalse(Files.exists(projectDir.resolve("target/classes/secret.txt")));
    }

    private static BuildSettings settings(
            List<GeneratedSourceStep> main,
            List<GeneratedSourceStep> test) {
        return BuildSettings.defaults().withGeneratedSources(main, test);
    }

    private static GeneratedSourceStep step(String id, String output) {
        return new GeneratedSourceStep(
                id,
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
                        "ksp-" + id,
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of()));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
