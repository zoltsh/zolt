package sh.zolt.build.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProtobufGenerationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ResourceCopierTest extends ResourceCopierTestSupport {
    private final ResourceCopier copier = new ResourceCopier();

    @Test
    void copiesExecTestResourcesUnderIntoSubtree() throws IOException {
        Files.createDirectories(projectDir.resolve("target/generated/test-fixtures"));
        Files.writeString(projectDir.resolve("target/generated/test-fixtures/seed.json"), "{}\n");
        BuildSettings settings = BuildSettings.defaults().withGeneratedSources(List.of(), List.of(execTestResourceStep()));

        ResourceCopyResult result = copier.copyTestResources(projectDir, settings);

        Path copied = projectDir.resolve("target/test-classes/data/seed.json");
        assertTrue(Files.exists(copied), "test-resources lane output must join the test resource copy under into");
        assertEquals("{}\n", Files.readString(copied));
        assertEquals(1, result.copiedCount());
    }

    private static GeneratedSourceStep execTestResourceStep() {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "project",
                ExecToolSettings.project("com.example.Fixtures"),
                List.of(),
                ProducesLane.TEST_RESOURCES,
                Optional.of("data"),
                Map.of(),
                "content");
        return new GeneratedSourceStep(
                "fixtures",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated/test-fixtures",
                List.of("target/test-classes"),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    @Test
    void copiesMainResourcesDeterministicallyPreservingRelativePaths() throws IOException {
        Path alpha = resource("src/main/resources/com/example/alpha.properties", "alpha=true\n");
        Path zeta = resource("src/main/resources/zeta.txt", "zeta\n");

        ResourceCopyResult result = copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertEquals(List.of(alpha, zeta), result.copiedResources());
        assertEquals(2, result.copiedCount());
        assertEquals("alpha=true\n", Files.readString(projectDir.resolve("target/classes/com/example/alpha.properties")));
        assertEquals("zeta\n", Files.readString(projectDir.resolve("target/classes/zeta.txt")));
    }

    @Test
    void copiesTestResourcesToTestOutput() throws IOException {
        Path fixture = resource("src/test/resources/fixtures/input.txt", "fixture\n");

        ResourceCopyResult result = copier.copyTestResources(projectDir, BuildSettings.defaults());

        assertEquals(List.of(fixture), result.copiedResources());
        assertEquals(1, result.resourceCount());
        assertEquals("fixture\n", Files.readString(projectDir.resolve("target/test-classes/fixtures/input.txt")));
    }

    @Test
    void skipsUnchangedResourcesOnRepeatedCopy() throws IOException {
        Path resource = resource("src/main/resources/application.properties", "name=demo\n");

        ResourceCopyResult first = copier.copyMainResources(projectDir, BuildSettings.defaults());
        ResourceCopyResult second = copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertEquals(List.of(resource), first.copiedResources());
        assertTrue(first.skippedResources().isEmpty());
        assertTrue(second.copiedResources().isEmpty());
        assertEquals(List.of(resource), second.skippedResources());
        assertEquals(1, second.resourceCount());
    }

    @Test
    void copiesChangedResourcesOnRepeatedCopy() throws IOException {
        Path resource = resource("src/main/resources/application.properties", "name=demo\n");
        copier.copyMainResources(projectDir, BuildSettings.defaults());
        Files.writeString(resource, "name=changed\n");

        ResourceCopyResult result = copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertEquals(List.of(resource), result.copiedResources());
        assertTrue(result.skippedResources().isEmpty());
        assertEquals("name=changed\n", Files.readString(projectDir.resolve("target/classes/application.properties")));
    }

    @Test
    void copiesConfiguredGeneratedResourceRoots() throws IOException {
        Path app = resource("src/main/resources/application.properties", "name=demo\n");
        Path generatedCss = resource("target/generated/resources/static/app.css", "body {}\n");
        BuildSettings settings = buildSettingsWithResourceRoots(
                List.of("src/main/resources", "target/generated/resources"),
                List.of("src/test/resources", "target/generated/test-resources"));

        ResourceCopyResult main = copier.copyMainResources(projectDir, settings);

        assertEquals(List.of(app, generatedCss), main.copiedResources());
        assertEquals("name=demo\n", Files.readString(projectDir.resolve("target/classes/application.properties")));
        assertEquals("body {}\n", Files.readString(projectDir.resolve("target/classes/static/app.css")));
    }

    @Test
    void skipsExistingBuildOutputsWhenConfiguredResourceRootContainsTarget() throws IOException {
        Path generated = resource("target/generated/resources/static/app.css", "body {}\n");
        resource("target/classes/stale.properties", "stale=true\n");
        resource("target/test-classes/fixture.properties", "test=true\n");
        BuildSettings settings = buildSettingsWithResourceRoots(
                List.of("target"),
                List.of("src/test/resources"));

        ResourceCopyResult result = copier.copyMainResources(projectDir, settings);

        assertEquals(List.of(generated), result.copiedResources());
        assertEquals(
                "body {}\n",
                Files.readString(projectDir.resolve("target/classes/generated/resources/static/app.css")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/classes/stale.properties")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/test-classes/fixture.properties")));
    }

    @Test
    void missingResourceDirectoriesReturnEmptyResults() {
        ResourceCopyResult main = copier.copyMainResources(projectDir, BuildSettings.defaults());
        ResourceCopyResult test = copier.copyTestResources(projectDir, BuildSettings.defaults());

        assertTrue(main.copiedResources().isEmpty());
        assertTrue(test.copiedResources().isEmpty());
    }

    @Test
    void skipsSourceFilesAndBuildOutputSegments() throws IOException {
        Path config = resource("src/main/resources/config/app.properties", "ok=true\n");
        resource("src/main/resources/com/example/NotAResource.java", "final class NotAResource {}\n");
        resource("src/main/resources/com/example/NotAResource.groovy", "class NotAResource {}\n");
        resource("src/main/resources/com/example/NotAResource.kt", "class NotAResource\n");
        Path kotlinScript = resource("src/main/resources/scripts/setup.kts", "println(\"setup\")\n");
        resource("src/main/resources/target/generated.txt", "target\n");
        resource("src/main/resources/build/generated.txt", "build\n");

        ResourceCopyResult result = copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertEquals(List.of(config, kotlinScript), result.copiedResources());
        assertTrue(Files.exists(projectDir.resolve("target/classes/config/app.properties")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/NotAResource.java")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/NotAResource.groovy")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/NotAResource.kt")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/scripts/setup.kts")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/target/generated.txt")));
        assertFalse(Files.exists(projectDir.resolve("target/classes/build/generated.txt")));
    }

    @Test
    void doesNotIgnoreNestedResourceDirectoryNamedBuild() throws IOException {
        Path resource = resource("src/main/resources/com/example/build/info.txt", "ok\n");

        ResourceCopyResult result = copier.copyMainResources(projectDir, BuildSettings.defaults());

        assertEquals(List.of(resource), result.copiedResources());
        assertEquals("ok\n", Files.readString(projectDir.resolve("target/classes/com/example/build/info.txt")));
    }
}
