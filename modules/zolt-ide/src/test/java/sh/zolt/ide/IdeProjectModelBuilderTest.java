package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeProjectModelBuilderTest {
    private final IdeProjectModelBuilder builder = new IdeProjectModelBuilder();

    @TempDir
    private Path tempDir;

    @Test
    void exportsRedactedTestRuntimeSettings() throws IOException {
        ProjectConfig config = parse("test-runtime", """
                [project]
                name = "test-runtime"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [test.runtime]
                jvmArgs = ["-Dconfigured=true"]
                properties = { "logs.dir" = "target/test-logs" }
                env = { TZ = "America/Chicago", SECRET_TOKEN = "do-not-export" }
                events = ["failed"]
                """);

        IdeModel.TestRuntimeInfo testRuntime = builder.testRuntimeInfo(config);

        assertEquals(List.of("-Dconfigured=true"), testRuntime.jvmArgs());
        assertEquals(
                Map.of("logs.dir", "target/test-logs"),
                testRuntime.systemProperties());
        assertEquals(
                Map.of("SECRET_TOKEN", "<redacted>", "TZ", "<redacted>"),
                testRuntime.environment());
        assertEquals(List.of("failed"), testRuntime.events());
        String json = new IdeModelJsonWriter().write(modelWith(testRuntime));
        assertTrue(json.contains("\"testRuntime\": {"));
        assertTrue(json.contains("\"SECRET_TOKEN\": \"<redacted>\""));
        assertTrue(json.contains("\"events\": ["));
    }

    @Test
    void exportsPackageArtifactSettingsAndPublicationMetadataForEditors() throws IOException {
        Path projectDir = tempDir.resolve("library-package");
        ProjectConfig config = parse(projectDir, """
                [project]
                name = "library-package"
                version = "0.1.0"
                group = "com.example"
                java = 21
                description = "Library packaging fixture"
                url = "https://example.com/library"
                issues = "https://example.com/library/issues"
                license = "Apache-2.0"

                [project.scm]
                url = "https://example.com/library.git"

                [project.developers.team]
                name = "Zolt Team"

                [build.output]
                root = ".zolt/build"

                [package]
                sources = true
                javadoc = true
                testJar = true

                [package.manifest]
                "Automatic-Module-Name" = "com.example.library"
                """);
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.PackageInfo packageInfo = builder.packageInfo(projectDir.toAbsolutePath().normalize(), config, diagnostics);

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(new IdeModel.PackageInfo(
                        "thin",
                        true,
                        true,
                        true,
                        root.resolve(".zolt/build/library-package-0.1.0.jar"),
                        root.resolve(".zolt/build/library-package-0.1.0-sources.jar"),
                        root.resolve(".zolt/build/library-package-0.1.0-javadoc.jar"),
                        root.resolve(".zolt/build/library-package-0.1.0-tests.jar"),
                        new IdeModel.PublicationInfo(
                                null,
                                "Library packaging fixture",
                                "https://example.com/library",
                                "Apache-2.0",
                                List.of("Zolt Team"),
                                "https://example.com/library.git",
                                "https://example.com/library/issues"),
                        Map.of("Automatic-Module-Name", "com.example.library")),
                packageInfo);
        assertEquals(
                new IdeModel.OutputInfo(
                        root.resolve(".zolt/build/classes"),
                        root.resolve(".zolt/build/test-classes"),
                        root.resolve(".zolt/build/library-package-0.1.0.jar")),
                builder.outputInfo(root, config, diagnostics));
        assertEquals(List.of(), diagnostics);

        String json = new IdeModelJsonWriter().write(modelWith(packageInfo));
        assertTrue(json.contains("\"package\": {"));
        assertTrue(json.contains("\"sourcesJar\": \""));
        assertTrue(json.contains("\"metadata\": {"));
        assertTrue(json.contains("\"developers\": ["));
        assertTrue(json.contains("\"manifestAttributes\": {"));
        assertTrue(json.contains("\"Automatic-Module-Name\": \"com.example.library\""));
    }

    private ProjectConfig parse(String directoryName, String toml) throws IOException {
        return parse(tempDir.resolve(directoryName), toml);
    }

    private ProjectConfig parse(Path projectDir, String toml) throws IOException {
        Files.createDirectories(projectDir);
        Path config = projectDir.resolve("zolt.toml");
        Files.writeString(config, toml);
        return new ManifestProjectConfigLoader().load(config);
    }

    private IdeModel modelWith(IdeModel.TestRuntimeInfo testRuntime) {
        return model(
                testRuntime,
                packageInfo(),
                new IdeModel.OutputInfo(null, null, null));
    }

    private IdeModel modelWith(IdeModel.PackageInfo packageInfo) {
        return model(
                new IdeModel.TestRuntimeInfo(List.of(), Map.of(), Map.of(), List.of()),
                packageInfo,
                new IdeModel.OutputInfo(null, null, null));
    }

    private IdeModel model(
            IdeModel.TestRuntimeInfo testRuntime,
            IdeModel.PackageInfo packageInfo,
            IdeModel.OutputInfo outputInfo) {
        return new IdeModel(
                1,
                new IdeModel.ProjectInfo("project", "com.example", "0.1.0", null),
                new IdeModel.JavaInfo("21", null, null),
                new IdeModel.CompilerInfo(null, null, null, List.of(), List.of(), null, null),
                testRuntime,
                packageInfo,
                new IdeModel.PathInfo(tempDir, tempDir.resolve("zolt.toml"), tempDir.resolve("zolt.lock")),
                List.of(),
                List.of(),
                List.of(),
                outputInfo,
                new IdeModel.DependencyInfo(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
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

    private static IdeModel.PackageInfo packageInfo() {
        return new IdeModel.PackageInfo(
                null,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                new IdeModel.PublicationInfo(null, null, null, null, List.of(), null, null),
                Map.of());
    }
}
