package sh.zolt.quarkus.testworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.doctor.JdkDetector;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptorReader;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptorWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusTestApplicationModelServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void skipsWhenQuarkusFrameworkIsDisabled() {
        assertTrue(service().writeIfEnabled(projectDir, config(BuildSettings.defaults(), false)).isEmpty());
    }

    @Test
    void requiresProjectDirectoryAndConfig() {
        assertTrue(assertThrows(
                        QuarkusAugmentationException.class,
                        () -> service().writeIfEnabled(null, config(BuildSettings.defaults())))
                .getMessage()
                .contains("requires a project directory"));
        assertTrue(assertThrows(
                        QuarkusAugmentationException.class,
                        () -> service().writeIfEnabled(projectDir, null))
                .getMessage()
                .contains("requires a project config"));
    }

    @Test
    void writesEnabledModelThroughWorkerLauncherWithWorkspaceInputs() throws IOException {
        writeBootstrapDescriptor();
        List<List<String>> commands = new ArrayList<>();
        QuarkusTestApplicationModelService service = new QuarkusTestApplicationModelService(
                new QuarkusBootstrapDescriptorReader(),
                new JdkDetector(),
                () -> List.of(projectDir.resolve("zolt-worker.jar")),
                (javaExecutable, workerClasspath) -> new QuarkusTestApplicationModelWorkerLauncher(
                        ":",
                        javaExecutable,
                        workerClasspath,
                        command -> {
                            commands.add(command);
                            try {
                                Files.writeString(Path.of(command.get(5)), "serialized model");
                            } catch (IOException exception) {
                                throw new AssertionError(exception);
                            }
                            return new QuarkusTestApplicationModelWorkerLauncher.ProcessResult(0, "");
                        }));

        Optional<Path> output = service.writeIfEnabled(projectDir, config(BuildSettings.defaults()));

        Path expectedOutput = projectDir.resolve("target/quarkus/test-application-model.dat")
                .toAbsolutePath()
                .normalize();
        assertEquals(Optional.of(expectedOutput), output);
        assertEquals("serialized model", Files.readString(expectedOutput));
        List<String> command = commands.getFirst();
        assertEquals(QuarkusTestApplicationModelWorker.MAIN_CLASS, command.get(3));
        assertEquals(projectDir.resolve("target/quarkus/zolt-bootstrap.properties").toString(), command.get(4));
        assertEquals(expectedOutput.toString(), command.get(5));
        assertEquals(projectDir.toAbsolutePath().normalize().toString(), command.get(6));
        assertEquals(projectDir.resolve("target").toAbsolutePath().normalize().toString(), command.get(7));
        assertEquals(projectDir.resolve("src/main/java").toAbsolutePath().normalize().toString(), command.get(8));
        assertEquals(projectDir.resolve("src/main/resources").toAbsolutePath().normalize().toString(), command.get(9));
        assertEquals(projectDir.resolve("target/classes").toAbsolutePath().normalize().toString(), command.get(10));
        assertEquals(projectDir.resolve("src/test/java").toAbsolutePath().normalize().toString(), command.get(11));
        assertEquals(projectDir.resolve("src/test/resources").toAbsolutePath().normalize().toString(), command.get(12));
        assertEquals(projectDir.resolve("target/test-classes").toAbsolutePath().normalize().toString(), command.get(13));
    }

    @Test
    void rejectsQuarkusModelOutputSymlinkThatEscapesProject() throws IOException {
        createSymlink(projectDir.resolve("target"), Files.createTempDirectory("zolt-quarkus-model-target-"));

        QuarkusAugmentationException exception = assertThrows(
                QuarkusAugmentationException.class,
                () -> service().writeIfEnabled(projectDir, config(BuildSettings.defaults())));

        assertTrue(exception.getMessage().contains("Quarkus test application model output"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void rejectsUnsafeWorkspaceModuleSourcePath() throws IOException {
        writeBootstrapDescriptor();

        QuarkusAugmentationException exception = assertThrows(
                QuarkusAugmentationException.class,
                () -> service().writeIfEnabled(
                        projectDir,
                        config(new BuildSettings(
                                "../outside-source",
                                "src/test/java",
                                "target/classes",
                                "target/test-classes"))));

        assertTrue(exception.getMessage().contains("[build].sources"));
        assertTrue(exception.getMessage().contains("../outside-source"));
    }

    private QuarkusTestApplicationModelService service() {
        return new QuarkusTestApplicationModelService(
                new QuarkusBootstrapDescriptorReader(),
                new JdkDetector(),
                () -> List.of(projectDir.resolve("zolt-worker.jar")),
                (javaExecutable, workerClasspath) -> new QuarkusTestApplicationModelWorkerLauncher(
                        ":",
                        javaExecutable,
                        workerClasspath,
                        command -> new QuarkusTestApplicationModelWorkerLauncher.ProcessResult(0, "")));
    }

    private ProjectConfig config(BuildSettings buildSettings) {
        return config(buildSettings, true);
    }

    private ProjectConfig config(BuildSettings buildSettings, boolean quarkusEnabled) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(
                                "demo",
                                "1.0.0",
                                "com.example",
                                String.valueOf(Runtime.version().feature()),
                                Optional.empty()),
                        ProjectConfig.defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        buildSettings)
                .withFrameworkSettings(new FrameworkSettings(new QuarkusSettings(
                        quarkusEnabled,
                        QuarkusPackageMode.FAST_JAR)));
    }

    private void writeBootstrapDescriptor() throws IOException {
        Path quarkusDirectory = projectDir.resolve("target/quarkus");
        Files.createDirectories(quarkusDirectory);
        Path runtimeClasspath = quarkusDirectory.resolve("runtime-classpath.txt");
        Path deploymentClasspath = quarkusDirectory.resolve("deployment-classpath.txt");
        Path applicationModel = quarkusDirectory.resolve("application-model.properties");
        Files.writeString(runtimeClasspath, "");
        Files.writeString(deploymentClasspath, "");
        Files.writeString(applicationModel, """
                version=1
                application.groupId=com.example
                application.artifactId=demo
                application.version=1.0.0
                application.classifier=
                application.type=jar
                application.path=%s
                dependencyCount=0
                """.formatted(projectDir.resolve("target/classes")));
        Files.writeString(quarkusDirectory.resolve("zolt-bootstrap.properties"), """
                version=1
                bootstrapClass=%s
                augmentActionClass=%s
                mode=prod
                package=fast-jar
                projectDirectory=%s
                applicationClasses=%s
                augmentationDirectory=%s
                packageDirectory=%s
                runtimeClasspathFile=%s
                deploymentClasspathFile=%s
                applicationModelFile=%s
                inputFingerprint=%s
                """.formatted(
                QuarkusBootstrapDescriptorWriter.BOOTSTRAP_CLASS,
                QuarkusBootstrapDescriptorWriter.AUGMENT_ACTION_CLASS,
                projectDir,
                projectDir.resolve("target/classes"),
                quarkusDirectory,
                projectDir.resolve("target/quarkus-app"),
                runtimeClasspath,
                deploymentClasspath,
                applicationModel,
                "sha256:" + "1".repeat(64)));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
