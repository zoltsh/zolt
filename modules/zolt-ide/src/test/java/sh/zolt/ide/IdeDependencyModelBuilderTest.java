package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeDependencyModelBuilderTest {
    private final IdeDependencyModelBuilder builder = new IdeDependencyModelBuilder();

    @TempDir
    private Path tempDir;

    @Test
    void exportsDependencyDeclarationsWithVisibility() throws IOException {
        IdeModel.DependencyInfo dependencies = builder.build(parseWorkspaceMember("dependencies", """
                [project]
                name = "app"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [platforms]
                "com.example:platform" = "1.0.0"

                [dependencies.api]
                "com.example:api-contract" = "1.0.0"
                "com.example:managed-api" = { managed = true }
                "com.example:workspace-api" = { workspace = true }

                [dependencies]
                "com.example:impl" = { version = "1.0.0", optional = true, exclude = ["com.example:legacy-logging"] }
                "com.example:publish-helper" = { version = "2.0.0", publishOnly = true }

                [dependencies.runtime]
                "com.example:runtime-only" = { managed = true }

                [dependencies.provided]
                "jakarta.servlet:jakarta.servlet-api" = "6.1.0"

                [dependencies.dev]
                "org.springframework.boot:spring-boot-devtools" = { managed = true }

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = { managed = true }

                [dependencies.processor]
                "com.example:processor" = "1.0.0"
                """));

        assertEquals(List.of(
                        new IdeModel.DependencyDeclaration("com.example:api-contract", "1.0.0", false, null),
                        new IdeModel.DependencyDeclaration("com.example:managed-api", null, true, null),
                        new IdeModel.DependencyDeclaration("com.example:workspace-api", null, false, "modules/api")),
                dependencies.api());
        assertEquals(
                List.of(
                        new IdeModel.DependencyDeclaration(
                                "com.example:impl",
                                "1.0.0",
                                false,
                                null,
                                true,
                                false,
                                List.of("com.example:legacy-logging")),
                        new IdeModel.DependencyDeclaration(
                                "com.example:publish-helper",
                                "2.0.0",
                                false,
                                null,
                                false,
                                true,
                                List.of())),
                dependencies.implementation());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration("com.example:runtime-only", null, true, null)),
                dependencies.runtime());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration("jakarta.servlet:jakarta.servlet-api", "6.1.0", false, null)),
                dependencies.provided());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration("org.springframework.boot:spring-boot-devtools", null, true, null)),
                dependencies.dev());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration("org.junit.jupiter:junit-jupiter", null, true, null)),
                dependencies.test());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration("com.example:processor", "1.0.0", false, null)),
                dependencies.annotationProcessors());

        String json = new IdeModelJsonWriter().write(modelWith(dependencies));
        assertTrue(json.contains("\"dependencies\": {"));
        assertTrue(json.contains("\"versionAliases\": {}"));
        assertTrue(json.contains("\"api\": ["));
        assertTrue(json.contains("\"implementation\": ["));
        assertTrue(json.contains("\"runtime\": ["));
        assertTrue(json.contains("\"provided\": ["));
        assertTrue(json.contains("\"dev\": ["));
        assertTrue(json.contains("\"coordinate\": \"com.example:workspace-api\""));
        assertTrue(json.contains("\"publishOnly\": true"));
        assertTrue(json.contains("\"exclusions\": ["));
        assertTrue(json.contains("\"workspace\": \"modules/api\""));
        assertTrue(json.contains("\"versionRef\": null"));
        assertTrue(json.contains("\"managed\": true"));
    }

    @Test
    void exportsVersionAliasesAndDependencyVersionRefs() throws IOException {
        IdeModel.DependencyInfo dependencies = builder.build(parse("alias-dependencies", """
                [project]
                name = "alias-dependencies"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                guava = "33.4.8-jre"
                junit = "5.12.1"

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava", optional = true }

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }
                """));

        assertEquals(Map.of("guava", "33.4.8-jre", "junit", "5.12.1"), dependencies.versionAliases());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration(
                        "com.google.guava:guava",
                        "33.4.8-jre",
                        "guava",
                        false,
                        null,
                        true,
                        false,
                        List.of())),
                dependencies.implementation());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration(
                        "org.junit.jupiter:junit-jupiter",
                        "5.12.1",
                        "junit",
                        false,
                        null,
                        false,
                        false,
                        List.of())),
                dependencies.test());

        String json = new IdeModelJsonWriter().write(modelWith(dependencies));
        assertTrue(json.contains("\"versionAliases\": {"));
        assertTrue(json.contains("\"guava\": \"33.4.8-jre\""));
        assertTrue(json.contains("\"versionRef\": \"guava\""));
        assertTrue(json.contains("\"versionRef\": \"junit\""));
    }

    @Test
    void exportsEveryDependencySectionInCoordinateOrder() throws IOException {
        IdeModel.DependencyInfo dependencies = builder.build(parse("ordered-dependencies", """
                [project]
                name = "ordered-dependencies"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies.api]
                "com.example:z-api" = "1.0.0"
                "com.example:a-api" = "1.0.0"

                [dependencies]
                "com.example:z-impl" = "1.0.0"
                "com.example:a-impl" = "1.0.0"

                [dependencies.runtime]
                "com.example:z-runtime" = "1.0.0"
                "com.example:a-runtime" = "1.0.0"

                [dependencies.provided]
                "com.example:z-provided" = "1.0.0"
                "com.example:a-provided" = "1.0.0"

                [dependencies.dev]
                "com.example:z-dev" = "1.0.0"
                "com.example:a-dev" = "1.0.0"

                [dependencies.test]
                "com.example:z-test" = "1.0.0"
                "com.example:a-test" = "1.0.0"

                [dependencies.processor]
                "com.example:z-processor" = "1.0.0"
                "com.example:a-processor" = "1.0.0"

                [dependencies.test-processor]
                "com.example:z-test-processor" = "1.0.0"
                "com.example:a-test-processor" = "1.0.0"
                """));

        assertEquals(List.of("com.example:a-api", "com.example:z-api"), coordinates(dependencies.api()));
        assertEquals(List.of("com.example:a-impl", "com.example:z-impl"), coordinates(dependencies.implementation()));
        assertEquals(List.of("com.example:a-runtime", "com.example:z-runtime"), coordinates(dependencies.runtime()));
        assertEquals(List.of("com.example:a-provided", "com.example:z-provided"), coordinates(dependencies.provided()));
        assertEquals(List.of("com.example:a-dev", "com.example:z-dev"), coordinates(dependencies.dev()));
        assertEquals(List.of("com.example:a-test", "com.example:z-test"), coordinates(dependencies.test()));
        assertEquals(
                List.of("com.example:a-processor", "com.example:z-processor"),
                coordinates(dependencies.annotationProcessors()));
        assertEquals(
                List.of("com.example:a-test-processor", "com.example:z-test-processor"),
                coordinates(dependencies.testAnnotationProcessors()));

        String json = new IdeModelJsonWriter().write(modelWith(dependencies));
        assertTrue(json.contains("\"testAnnotationProcessors\": ["));
        assertTrue(json.indexOf("\"coordinate\": \"com.example:a-test-processor\"")
                < json.indexOf("\"coordinate\": \"com.example:z-test-processor\""));
    }

    @Test
    void dependencyInfoTreatsNullVersionAliasesAsEmptyMap() {
        IdeModel.DependencyInfo dependencies = new IdeModel.DependencyInfo(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertEquals(Map.of(), dependencies.versionAliases());
        String json = new IdeModelJsonWriter().write(modelWith(dependencies));
        assertTrue(json.contains("\"versionAliases\": {}"));
    }

    @Test
    void returnsEmptyDependencyModelWhenProjectConfigIsUnavailable() {
        IdeModel.DependencyInfo dependencies = builder.build(null);

        assertEquals(Map.of(), dependencies.versionAliases());
        assertEquals(List.of(), dependencies.api());
        assertEquals(List.of(), dependencies.implementation());
        assertEquals(List.of(), dependencies.runtime());
        assertEquals(List.of(), dependencies.provided());
        assertEquals(List.of(), dependencies.dev());
        assertEquals(List.of(), dependencies.test());
        assertEquals(List.of(), dependencies.annotationProcessors());
        assertEquals(List.of(), dependencies.testAnnotationProcessors());
    }

    @Test
    void dependencyMetadataIsExportedOnceInCoordinateOrder() throws IOException {
        IdeModel.DependencyInfo dependencies = builder.build(parse("publish-only-managed", """
                [project]
                name = "publish-only-managed"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [platforms]
                "com.example:platform" = "1.0.0"

                [dependencies]
                "com.example:z-helper" = { version = "1.0.0", publishOnly = true }
                "com.example:a-managed-helper" = { managed = true, optional = true }
                "com.example:m-helper" = "1.0.0"
                """));

        assertEquals(
                List.of("com.example:a-managed-helper", "com.example:m-helper", "com.example:z-helper"),
                coordinates(dependencies.implementation()));
        assertEquals(new IdeModel.DependencyDeclaration(
                        "com.example:a-managed-helper",
                        null,
                        null,
                        true,
                        null,
                        true,
                        false,
                        List.of()),
                dependencies.implementation().getFirst());
        assertEquals(new IdeModel.DependencyDeclaration(
                        "com.example:z-helper",
                        "1.0.0",
                        null,
                        false,
                        null,
                        false,
                        true,
                        List.of()),
                dependencies.implementation().get(2));
    }

    private static List<String> coordinates(List<IdeModel.DependencyDeclaration> declarations) {
        return declarations.stream()
                .map(IdeModel.DependencyDeclaration::coordinate)
                .toList();
    }

    private ProjectConfig parse(String directoryName, String toml) throws IOException {
        Path projectDir = tempDir.resolve(directoryName);
        Files.createDirectories(projectDir);
        Path config = projectDir.resolve("zolt.toml");
        Files.writeString(config, toml);
        return new ManifestProjectConfigLoader().load(config);
    }

    /**
     * Loads {@code toml} as the {@code app} member of a two-member workspace so a
     * {@code workspace = true} dependency resolves to the {@code modules/api} provider.
     */
    private ProjectConfig parseWorkspaceMember(String directoryName, String toml) throws IOException {
        Path root = tempDir.resolve(directoryName);
        Path member = root.resolve("app");
        Path provider = root.resolve("modules/api");
        Files.createDirectories(member);
        Files.createDirectories(provider);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "ide-fixtures"

                [workspace.members]
                include = ["app", "modules/api"]
                """);
        Files.writeString(member.resolve("zolt.toml"), toml);
        Files.writeString(provider.resolve("zolt.toml"), """
                [project]
                name = "workspace-api"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        return new ManifestWorkspaceLoader().load(root).members().stream()
                .filter(candidate -> candidate.path().equals("app"))
                .findFirst()
                .orElseThrow()
                .config();
    }

    private IdeModel modelWith(IdeModel.DependencyInfo dependencies) {
        return new IdeModel(
                1,
                new IdeModel.ProjectInfo("dependencies", "com.example", "0.1.0", null),
                new IdeModel.JavaInfo("21", null, null),
                new IdeModel.CompilerInfo(null, null, null, List.of(), List.of(), null, null),
                new IdeModel.TestRuntimeInfo(List.of(), Map.of(), Map.of(), List.of()),
                new IdeModel.PackageInfo(
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        new IdeModel.PublicationInfo(null, null, null, null, List.of(), null, null),
                        Map.of()),
                new IdeModel.PathInfo(tempDir, tempDir.resolve("zolt.toml"), tempDir.resolve("zolt.lock")),
                List.of(),
                List.of(),
                List.of(),
                new IdeModel.OutputInfo(null, null, null),
                dependencies,
                new IdeModel.ClasspathInfo(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                new IdeModel.FrameworkInfo(new IdeModel.QuarkusInfo(
                        false,
                        null,
                        "disabled",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of())),
                List.of());
    }
}
