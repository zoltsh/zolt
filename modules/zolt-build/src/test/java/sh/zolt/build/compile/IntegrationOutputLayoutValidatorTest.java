package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IntegrationOutputLayoutValidatorTest {
    @TempDir
    private Path projectDir;

    @Test
    void mainOutputCannotOverlapIntegrationTestOutput() {
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/integration-test-classes",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(projectDir, config(build)));

        assertTrue(exception.getMessage().contains("[build.output].integration"), exception.getMessage());
    }

    @Test
    void projectedIntegrationScopeMayUseItsOwnOutput() {
        BuildSettings build = BuildSettings.defaults()
                .withIntegrationTestSettings(
                        "target/integration-test-classes",
                        List.of("src/integration-test/java", "src/integration-test/kotlin"),
                        List.of("src/integration-test/resources"))
                .asIntegrationTestBuild();

        assertTrue(build.testSources().equals(build.kotlinTestSources()));
        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(projectDir, config(build)));
    }

    @Test
    void originalIntegrationSettingsRejectOverlapBeforeProjection() {
        BuildSettings original = BuildSettings.defaults().withIntegrationTestSettings(
                "target/test-classes/integration",
                List.of("src/integration-test/java"),
                List.of("src/integration-test/resources"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(projectDir, config(original)));

        assertTrue(exception.getMessage().contains("[build.output].integration"), exception.getMessage());
    }

    @Test
    void distinctIntegrationSettingsAndTheirProjectionAreSafe() {
        BuildSettings original = BuildSettings.defaults();

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(projectDir, config(original)));
        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(
                projectDir,
                config(original.asIntegrationTestBuild())));
    }

    private static ProjectConfig config(BuildSettings build) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "p",
                        currentJavaMajorVersion(),
                        Optional.of("p.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                build,
                NativeSettings.defaults(),
                CompilerSettings.defaults());
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
