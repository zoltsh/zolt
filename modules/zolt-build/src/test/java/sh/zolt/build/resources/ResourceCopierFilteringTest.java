package sh.zolt.build.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.ResourceCopyException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourceCopierFilteringTest {
    private final ResourceCopier copier = new ResourceCopier();

    @TempDir
    private Path projectDir;

    @Test
    void filtersMainResourcesWithLiteralAndProjectTokens() throws IOException {
        Path resource = resource("src/main/resources/application.properties", """
                name=@projectName@
                version=@projectVersion@
                greeting=@greeting@
                """);
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of(
                        "projectName", ResourceTokenSettings.project("name"),
                        "projectVersion", ResourceTokenSettings.project("version"),
                        "greeting", ResourceTokenSettings.literal("hello")));

        ResourceCopyResult result = copier.copyMainResources(projectDir, config(filtering));

        assertEquals(List.of(resource), result.copiedResources());
        assertEquals("""
                name=demo
                version=0.1.0
                greeting=hello
                """, Files.readString(projectDir.resolve("target/classes/application.properties")));
    }

    @Test
    void skipsUnchangedFilteredResourcesOnRepeatedCopy() throws IOException {
        Path resource = resource("src/main/resources/application.properties", "name=@projectName@\n");
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of("projectName", ResourceTokenSettings.project("name")));

        ResourceCopyResult first = copier.copyMainResources(projectDir, config(filtering));
        ResourceCopyResult second = copier.copyMainResources(projectDir, config(filtering));

        assertEquals(List.of(resource), first.copiedResources());
        assertTrue(second.copiedResources().isEmpty());
        assertEquals(List.of(resource), second.skippedResources());
    }

    @Test
    void testResourceFilteringRequiresExplicitOptIn() throws IOException {
        resource("src/test/resources/application.properties", "name=@projectName@\n");
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of("projectName", ResourceTokenSettings.project("name")));

        copier.copyTestResources(projectDir, config(filtering));

        assertEquals(
                "name=@projectName@\n",
                Files.readString(projectDir.resolve("target/test-classes/application.properties")));
    }

    @Test
    void filtersTestResourcesWhenExplicitlyEnabled() throws IOException {
        resource("src/test/resources/application.properties", "name=@projectName@\n");
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                true,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of("projectName", ResourceTokenSettings.project("name")));

        copier.copyTestResources(projectDir, config(filtering));

        assertEquals(
                "name=demo\n",
                Files.readString(projectDir.resolve("target/test-classes/application.properties")));
    }

    @Test
    void missingFilteringTokenFailsClearlyByDefault() throws IOException {
        resource("src/main/resources/application.properties", "name=@missing@\n");
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of());

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, config(filtering)));

        assertTrue(exception.getMessage().contains("contains token @missing@"));
        assertTrue(exception.getMessage().contains("[resources.tokens].missing is not defined"));
        assertTrue(exception.getMessage().contains("[resources.filter].missing = \"keep\""));
    }

    @Test
    void missingFilteringTokenCanRemainUnchanged() throws IOException {
        resource("src/main/resources/application.properties", "name=@missing@\n");
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.KEEP,
                Map.of());

        copier.copyMainResources(projectDir, config(filtering));

        assertEquals(
                "name=@missing@\n",
                Files.readString(projectDir.resolve("target/classes/application.properties")));
    }

    @Test
    void selectedBinaryResourceFilteringFailsClearly() throws IOException {
        Path resource = projectDir.resolve("src/main/resources/logo.bin");
        Files.createDirectories(resource.getParent());
        Files.write(resource, new byte[] {1, 2, 0, 4});
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.bin"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of());

        ResourceCopyException exception = assertThrows(
                ResourceCopyException.class,
                () -> copier.copyMainResources(projectDir, config(filtering)));

        assertTrue(exception.getMessage().contains("logo.bin"));
        assertTrue(exception.getMessage().contains("appears to be binary"));
    }

    private Path resource(String path, String content) throws IOException {
        Path resource = projectDir.resolve(path);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, content);
        return resource.normalize();
    }

    private static ProjectConfig config(ResourceFilteringSettings filtering) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                        ProjectConfig.defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withBuildSettings(BuildSettings.defaults().withResourceFiltering(filtering));
    }
}
