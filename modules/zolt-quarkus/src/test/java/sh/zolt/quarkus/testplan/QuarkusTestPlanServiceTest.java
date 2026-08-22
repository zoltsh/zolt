package sh.zolt.quarkus.testplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.quarkus.QuarkusPlanException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusTestPlanServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void reportsPlainJUnitPlanWhenCompiledTestOutputIsMissing() {
        QuarkusTestPlan plan = new QuarkusTestPlanService().plan(projectDir, config(true));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(root, plan.projectDirectory());
        assertEquals(root.resolve("target/test-classes"), plan.testOutputDirectory());
        assertEquals(root.resolve("target/quarkus/test-application-model.dat"), plan.serializedApplicationModel());
        assertEquals(root.resolve("target/quarkus/zolt-test-bootstrap.properties"), plan.testRunnerDescriptor());
        assertFalse(plan.testOutputPresent());
        assertTrue(plan.unsupportedTests().isEmpty());
    }

    @Test
    void derivesQuarkusTestPlanOutputFromBuildOutputRoot() {
        QuarkusTestPlan plan = new QuarkusTestPlanService().plan(
                projectDir,
                config(true, new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        ".zolt/build",
                        ".zolt/build/classes",
                        ".zolt/build/test-classes")));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(root.resolve(".zolt/build/test-classes"), plan.testOutputDirectory());
        assertEquals(root.resolve(".zolt/build/quarkus/test-application-model.dat"), plan.serializedApplicationModel());
        assertEquals(root.resolve(".zolt/build/quarkus/zolt-test-bootstrap.properties"), plan.testRunnerDescriptor());
    }

    @Test
    void usesSharedScannerForQuarkusTestAnnotations() {
        Path testOutput = projectDir.toAbsolutePath().normalize().resolve("target/test-classes");
        QuarkusTestPlan plan = new QuarkusTestPlanService(path -> List.of(new QuarkusUnsupportedTest(
                        path.resolve("com/example/HttpTest.class"),
                        Path.of("com/example/HttpTest.class"),
                        "@QuarkusTest",
                        true)))
                .plan(projectDir, config(true));

        assertEquals(testOutput, plan.testOutputDirectory());
        assertEquals(1, plan.unsupportedTests().size());
        assertEquals(1, plan.annotationRunnerTests().size());
        assertFalse(plan.hasUnsupportedTests());
        assertEquals(Path.of("com/example/HttpTest.class"), plan.unsupportedTests().getFirst().relativePath());
    }

    @Test
    void selectsEachAnnotationRunnerClassOnlyOnceWhenSupportedMarkersOverlap() {
        Path testOutput = projectDir.toAbsolutePath().normalize().resolve("target/test-classes");
        QuarkusUnsupportedTest quarkusTest = new QuarkusUnsupportedTest(
                testOutput.resolve("com/example/ProfiledHttpTest.class"),
                Path.of("com/example/ProfiledHttpTest.class"),
                "@QuarkusTest",
                true);
        QuarkusUnsupportedTest profileModifier = new QuarkusUnsupportedTest(
                testOutput.resolve("com/example/ProfiledHttpTest.class"),
                Path.of("com/example/ProfiledHttpTest.class"),
                "@TestProfile",
                true);
        QuarkusTestPlan plan = new QuarkusTestPlanService(path -> List.of(profileModifier, quarkusTest))
                .plan(projectDir, config(true));

        assertFalse(plan.hasUnsupportedTests());
        assertEquals(List.of(quarkusTest), plan.annotationRunnerTests());
    }

    @Test
    void rejectsWhenQuarkusIsDisabled() {
        QuarkusPlanException exception = assertThrows(
                QuarkusPlanException.class,
                () -> new QuarkusTestPlanService().plan(projectDir, config(false)));

        assertTrue(exception.getMessage().contains("Quarkus is not enabled for this project"));
        assertTrue(exception.getMessage().contains("zolt quarkus test-plan"));
    }

    @Test
    void rejectsUnsafeTestOutputPath() {
        QuarkusPlanException exception = assertThrows(
                QuarkusPlanException.class,
                () -> new QuarkusTestPlanService().plan(
                        projectDir,
                        config(true, new BuildSettings(
                                "src/main/java",
                                "src/test/java",
                                "target/classes",
                                "../outside-test-classes"))));

        assertTrue(exception.getMessage().contains("[build.output].test"));
        assertTrue(exception.getMessage().contains("../outside-test-classes"));
    }

    @Test
    void rejectsQuarkusTestOutputSymlinkThatEscapesProject() throws IOException {
        createSymlink(projectDir.resolve("target"), Files.createTempDirectory("zolt-quarkus-test-target-"));

        QuarkusPlanException exception = assertThrows(
                QuarkusPlanException.class,
                () -> new QuarkusTestPlanService().plan(
                        projectDir,
                        config(true, new BuildSettings(
                                "src/main/java",
                                "src/test/java",
                                "classes",
                                "test-classes"))));

        assertTrue(exception.getMessage().contains("Quarkus test output"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    private static ProjectConfig config(boolean quarkusEnabled) {
        return config(quarkusEnabled, BuildSettings.defaults());
    }

    private static ProjectConfig config(boolean quarkusEnabled, BuildSettings buildSettings) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                        ProjectConfig.defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        buildSettings)
                .withFrameworkSettings(new FrameworkSettings(new QuarkusSettings(
                        quarkusEnabled,
                        QuarkusPackageMode.FAST_JAR)));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
