package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CompileOutputLayoutValidatorTest {
    @TempDir
    private Path projectDir;

    @Test
    void mainOutputCannotOwnProjectLockfile() throws IOException {
        Path lockfile = projectDir.resolve("zolt.lock");
        Files.writeString(lockfile, "sentinel\n");
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "zolt.lock",
                "target/test-classes");
        ProjectConfig config = config(build, CompilerSettings.defaults());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetMain(
                        projectDir,
                        config,
                        lockfile,
                        projectDir.resolve(config.compilerSettings().generatedSources())));

        assertTrue(exception.getMessage().contains("project lockfile"), exception.getMessage());
        assertEquals("sentinel\n", Files.readString(lockfile));
    }

    @Test
    void mainGeneratedOutputCannotOwnProjectLockfile() throws IOException {
        Path lockfile = projectDir.resolve("zolt.lock");
        Files.writeString(lockfile, "sentinel\n");
        ProjectConfig config = config(
                BuildSettings.defaults(),
                new CompilerSettings("zolt.lock", "target/generated/test-sources/annotations"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetMain(
                        projectDir,
                        config,
                        projectDir.resolve(config.build().output()),
                        lockfile));

        assertTrue(exception.getMessage().contains("project lockfile"), exception.getMessage());
        assertEquals("sentinel\n", Files.readString(lockfile));
    }

    @Test
    void testOutputCannotOwnProjectLockfile() throws IOException {
        Path lockfile = projectDir.resolve("zolt.lock");
        Files.writeString(lockfile, "sentinel\n");
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/classes",
                "zolt.lock");
        ProjectConfig config = config(build, CompilerSettings.defaults());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetTest(
                        projectDir,
                        config,
                        lockfile,
                        projectDir.resolve(config.compilerSettings().generatedTestSources())));

        assertTrue(exception.getMessage().contains("project lockfile"), exception.getMessage());
        assertEquals("sentinel\n", Files.readString(lockfile));
    }

    @Test
    void generatedOutputCannotContainMainSourceRoot() throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        ProjectConfig config = config(
                BuildSettings.defaults(),
                new CompilerSettings("src", "target/generated/test-sources/annotations"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(projectDir, config));

        assertTrue(exception.getMessage().contains("[compiler.generated].main"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
    }

    @Test
    void mainOutputCannotBeNestedInsideMainSourceRoot() throws IOException {
        Path source = projectDir.resolve("src/main/java/compiled/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "src/main/java/compiled",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetMain(
                        projectDir,
                        config(build, CompilerSettings.defaults()),
                        projectDir.resolve(build.output()),
                        projectDir.resolve(CompilerSettings.defaults().generatedSources())));

        assertTrue(exception.getMessage().contains("[build.output].main"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("is nested within protected project input"), exception.getMessage());
        assertEquals("package p; public final class Main {}\n", Files.readString(source));
    }

    @Test
    void mainGeneratedOutputCannotBeNestedInsideMainResourceRoot() throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/resources"));
        BuildSettings build = buildWithResources(
                "target/classes",
                "target/test-classes",
                List.of("src/main/resources"),
                List.of("src/test/resources"));
        CompilerSettings compiler = new CompilerSettings(
                "src/main/resources/annotations",
                "target/generated/test-sources/annotations");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(projectDir, config(build, compiler)));

        assertTrue(exception.getMessage().contains("[compiler.generated].main"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[resources].main[0]"), exception.getMessage());
    }

    @Test
    void testOutputCannotBeNestedInsideTestSourceRoot() throws IOException {
        Files.createDirectories(projectDir.resolve("src/test/java"));
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/classes",
                "src/test/java/compiled");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[build.output].test"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[test.sources].java[0]"), exception.getMessage());
    }

    @Test
    void testOutputCannotBeNestedInsideKotlinTestSourceRoot() {
        BuildSettings defaults = BuildSettings.defaults();
        BuildSettings build = new BuildSettings(
                defaults.source(),
                defaults.sourceRoots(),
                defaults.test(),
                defaults.outputRoot(),
                defaults.output(),
                "src/test/kotlin/compiled",
                defaults.testSources(),
                defaults.groovyTestSources(),
                List.of("src/test/kotlin"),
                defaults.integrationTestOutput(),
                defaults.integrationTestSources(),
                defaults.integrationTestResourceRoots(),
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.resourceFiltering(),
                defaults.testRuntime(),
                defaults.testSuites(),
                defaults.metadata(),
                defaults.generatedMainSources(),
                defaults.generatedTestSources());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[build.output].test"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[test.sources].kotlin[0]"), exception.getMessage());
    }

    @Test
    void testGeneratedOutputCannotBeNestedInsideTestResourceRoot() throws IOException {
        Path resource = projectDir.resolve("src/test/resources/annotations/application.properties");
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "sentinel=true\n");
        BuildSettings build = buildWithResources(
                "target/classes",
                "target/test-classes",
                List.of("src/main/resources"),
                List.of("src/test/resources"));
        CompilerSettings compiler = new CompilerSettings(
                "target/generated/sources/annotations",
                "src/test/resources/annotations");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetTest(
                        projectDir,
                        config(build, compiler),
                        projectDir.resolve(build.testOutput()),
                        projectDir.resolve(compiler.generatedTestSources())));

        assertTrue(exception.getMessage().contains("[compiler.generated].test"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[resources].test[0]"), exception.getMessage());
        assertEquals("sentinel=true\n", Files.readString(resource));
    }

    @Test
    void testOutputCannotOverlapMainOutput() throws IOException {
        Files.createDirectories(projectDir.resolve("target/classes"));
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/classes",
                "target/classes/tests");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("overlaps another compile scope"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build.output].main"), exception.getMessage());
    }

    @Test
    void symlinkAliasToSourceRootIsRejected() throws IOException {
        Path sourceRoot = projectDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Path outputAlias = projectDir.resolve("classes-link");
        try {
            Files.createSymbolicLink(outputAlias, sourceRoot);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "classes-link",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("contains protected project input"), exception.getMessage());
    }

    @Test
    void symlinkedMainOutputDescendantOfSourceRootIsRejected() throws IOException {
        Path sourceRoot = projectDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Path outputAlias = projectDir.resolve("classes-link");
        createSymlink(outputAlias, sourceRoot);
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "classes-link/compiled",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("is nested within protected project input"), exception.getMessage());
    }

    @Test
    void symlinkedTestGeneratedOutputDescendantOfResourceRootIsRejected() throws IOException {
        Path resourceRoot = projectDir.resolve("src/test/resources");
        Files.createDirectories(resourceRoot);
        Path outputAlias = projectDir.resolve("test-resources-link");
        createSymlink(outputAlias, resourceRoot);
        BuildSettings build = buildWithResources(
                "target/classes",
                "target/test-classes",
                List.of("src/main/resources"),
                List.of("src/test/resources"));
        CompilerSettings compiler = new CompilerSettings(
                "target/generated/sources/annotations",
                "test-resources-link/annotations");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(projectDir, config(build, compiler)));

        assertTrue(exception.getMessage().contains("is nested within protected project input"), exception.getMessage());
    }

    @Test
    void symlinkedOutputRootCannotClaimAuthoredProjectRootSubtree() throws IOException {
        Path source = projectDir.resolve("src/main/java/classes/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        createSymlink(projectDir.resolve("target"), projectDir.resolve("src/main/java"));
        BuildSettings build = new BuildSettings(
                ".",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");
        ProjectConfig config = config(build, CompilerSettings.defaults());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetMain(
                        projectDir,
                        config,
                        projectDir.resolve(build.output()),
                        projectDir.resolve(config.compilerSettings().generatedSources())));

        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertTrue(exception.getMessage().contains("is nested within protected project input"), exception.getMessage());
        assertEquals("package p; public final class Main {}\n", Files.readString(source));
    }

    @Test
    void sourceRootAliasToProjectRootDoesNotReceiveCatchAllOwnership() throws IOException {
        Path source = projectDir.resolve("target/classes/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        createSymlink(projectDir.resolve("project-view"), projectDir);
        BuildSettings build = new BuildSettings(
                "project-view",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");
        ProjectConfig config = config(build, CompilerSettings.defaults());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputCleaner.resetMain(
                        projectDir,
                        config,
                        projectDir.resolve(build.output()),
                        projectDir.resolve(config.compilerSettings().generatedSources())));

        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertEquals("package p; public final class Main {}\n", Files.readString(source));
    }

    @Test
    void projectRootSourceMayContainConventionalOutput() {
        BuildSettings build = new BuildSettings(
                ".",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateMain(
                projectDir, config(build, CompilerSettings.defaults())));
        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(
                projectDir, config(build, CompilerSettings.defaults())));
    }

    @Test
    void projectRootSourceDoesNotExemptOutputOutsideDeclaredOutputRoot() {
        BuildSettings build = new BuildSettings(
                ".",
                "src/test/java",
                "target",
                "compiled/main",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
    }

    private static BuildSettings buildWithResources(
            String mainOutput,
            String testOutput,
            List<String> mainResources,
            List<String> testResources) {
        return new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                mainOutput,
                testOutput,
                List.of("src/test/java"),
                List.of(),
                mainResources,
                testResources,
                BuildMetadataSettings.defaults());
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private static ProjectConfig config(BuildSettings build, CompilerSettings compiler) {
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
                compiler);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
