package sh.zolt.build.clean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CleanServiceOutputTest extends CleanServiceTestSupport {
    @Test
    void deletesDefaultTargetDirectory() throws IOException {
        file("target/classes/com/example/Main.class");
        file("target/test-classes/com/example/MainTest.class");
        file("target/generated/sources/annotations/com/example/Generated.java");

        CleanResult result = cleanService.clean(projectDir, BuildSettings.defaults());

        assertEquals(1, result.deletedCount());
        assertEquals(projectDir.resolve("target"), result.deletedPaths().getFirst());
        assertFalse(Files.exists(projectDir.resolve("target")));
    }

    @Test
    void sharedTargetCleanPreservesConfiguredSourceDescendant() throws IOException {
        file("target/src/p/Main.java");
        file("target/classes/p/Main.class");
        file("target/test-classes/p/MainTest.class");
        file("target/integration-test-classes/p/MainIT.class");
        BuildSettings settings = new BuildSettings(
                "target/src",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        CleanResult result = cleanService.clean(projectDir, settings);

        assertEquals(3, result.deletedCount());
        assertTrue(Files.exists(projectDir.resolve("target/src/p/Main.java")));
        assertFalse(Files.exists(projectDir.resolve("target/classes")));
        assertFalse(Files.exists(projectDir.resolve("target/test-classes")));
        assertFalse(Files.exists(projectDir.resolve("target/integration-test-classes")));
    }

    @Test
    void sharedTargetCleanPreservesConfiguredResourceDescendant() throws IOException {
        file("target/resources/application.properties");
        file("target/classes/p/Main.class");
        file("target/test-classes/p/MainTest.class");
        BuildSettings settings = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of(),
                List.of("target/resources"),
                List.of("src/test/resources"),
                BuildMetadataSettings.defaults());

        CleanResult result = cleanService.clean(projectDir, settings);

        assertEquals(2, result.deletedCount());
        assertTrue(Files.exists(projectDir.resolve("target/resources/application.properties")));
        assertFalse(Files.exists(projectDir.resolve("target/classes")));
        assertFalse(Files.exists(projectDir.resolve("target/test-classes")));
    }

    @Test
    void deletesOutputRootWithoutDeletingMavenTargetOrGradleBuild() throws IOException {
        file(".zolt/build/classes/com/example/Main.class");
        file(".zolt/build/test-classes/com/example/MainTest.class");
        file("target/classes/com/example/MavenMain.class");
        file("build/classes/java/main/com/example/GradleMain.class");
        BuildSettings settings = new BuildSettings(
                "src/main/java",
                "src/test/java",
                ".zolt/build",
                ".zolt/build/classes",
                ".zolt/build/test-classes");

        CleanResult result = cleanService.clean(projectDir, settings);

        assertEquals(1, result.deletedCount());
        assertEquals(projectDir.resolve(".zolt/build"), result.deletedPaths().getFirst());
        assertFalse(Files.exists(projectDir.resolve(".zolt/build")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/MavenMain.class")));
        assertTrue(Files.exists(projectDir.resolve("build/classes/java/main/com/example/GradleMain.class")));
    }

    @Test
    void deletesCustomOutputDirectories() throws IOException {
        file("out/main/Main.class");
        file("out-test/test/MainTest.class");
        file("target/generated/sources/annotations/com/example/Generated.java");
        file("target/generated/test-sources/annotations/com/example/GeneratedTest.java");

        CleanResult result = cleanService.clean(
                projectDir,
                new BuildSettings("src/main/java", "src/test/java", "out/main", "out-test/test"),
                CompilerSettings.defaults());

        assertEquals(4, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve("out/main")));
        assertFalse(Files.exists(projectDir.resolve("out-test/test")));
        assertFalse(Files.exists(projectDir.resolve("target/generated/sources/annotations")));
        assertFalse(Files.exists(projectDir.resolve("target/generated/test-sources/annotations")));
    }

    @Test
    void deletesConfiguredGeneratedSourceDirectories() throws IOException {
        file("out/main/Main.class");
        file("out-test/test/MainTest.class");
        file("build/generated/main/com/example/Generated.java");
        file("build/generated/test/com/example/GeneratedTest.java");

        CleanResult result = cleanService.clean(
                projectDir,
                new BuildSettings("src/main/java", "src/test/java", "out/main", "out-test/test"),
                new CompilerSettings("build/generated/main", "build/generated/test"));

        assertEquals(4, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve("build/generated/main")));
        assertFalse(Files.exists(projectDir.resolve("build/generated/test")));
    }

    @Test
    void deletesChainedExecOutputsWithoutDeletingAuthoredInputs() throws IOException {
        Path script = projectDir.resolve("scripts/stage.sh");
        Path seed = projectDir.resolve("src/main/exec/seed.txt");
        file("scripts/stage.sh");
        file("src/main/exec/seed.txt");
        file("target/generated/exec/stage/staged.txt");
        file("target/generated/exec/resource/generated.properties");
        GeneratedSourceStep stage = new GeneratedSourceStep(
                "stage",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated/exec/stage",
                List.of("scripts/stage.sh", "src/main/exec/seed.txt"),
                true,
                true);
        GeneratedSourceStep bundle = new GeneratedSourceStep(
                "bundle",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated/exec/resource",
                List.of("scripts/stage.sh", "target/generated/exec/stage"),
                true,
                true);
        BuildSettings settings = BuildSettings.defaults().withGeneratedSources(
                List.of(stage, bundle),
                List.of());

        cleanService.clean(projectDir, settings);

        assertFalse(Files.exists(projectDir.resolve("target")));
        assertEquals("x", Files.readString(script));
        assertEquals("x", Files.readString(seed));
    }

    @Test
    void deletesQuarkusOutputsWhenFrameworkIsEnabled() throws IOException {
        file("out/main/Main.class");
        file("out-test/test/MainTest.class");
        file("target/quarkus/zolt-augmentation.properties");
        file("target/quarkus-app/quarkus-run.jar");

        CleanResult result = cleanService.clean(
                projectDir,
                config(new BuildSettings("src/main/java", "src/test/java", "out/main", "out-test/test"), true));

        assertEquals(4, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve("out/main")));
        assertFalse(Files.exists(projectDir.resolve("out-test/test")));
        assertFalse(Files.exists(projectDir.resolve("target/quarkus")));
        assertFalse(Files.exists(projectDir.resolve("target/quarkus-app")));
    }

    @Test
    void deletesFrameworkOutputsUnderOutputRoot() throws IOException {
        file(".zolt/build/classes/com/example/Main.class");
        file(".zolt/build/quarkus/zolt-augmentation.properties");
        file(".zolt/build/quarkus-app/quarkus-run.jar");
        file("target/quarkus/zolt-augmentation.properties");
        file("target/quarkus-app/quarkus-run.jar");
        BuildSettings settings = new BuildSettings(
                "src/main/java",
                "src/test/java",
                ".zolt/build",
                ".zolt/build/classes",
                ".zolt/build/test-classes");

        CleanResult result = cleanService.clean(projectDir, config(settings, true));

        assertEquals(1, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve(".zolt/build")));
        assertTrue(Files.exists(projectDir.resolve("target/quarkus/zolt-augmentation.properties")));
        assertTrue(Files.exists(projectDir.resolve("target/quarkus-app/quarkus-run.jar")));
    }

    @Test
    void leavesQuarkusOutputsWhenFrameworkIsDisabled() throws IOException {
        file("out/main/Main.class");
        file("target/quarkus/zolt-augmentation.properties");
        file("target/quarkus-app/quarkus-run.jar");

        CleanResult result = cleanService.clean(
                projectDir,
                config(new BuildSettings("src/main/java", "src/test/java", "out/main", "out-test/test"), false));

        assertEquals(1, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve("out/main")));
        assertTrue(Files.exists(projectDir.resolve("target/quarkus/zolt-augmentation.properties")));
        assertTrue(Files.exists(projectDir.resolve("target/quarkus-app/quarkus-run.jar")));
    }

    @Test
    void missingOutputDirectoriesAreCleanNoOp() {
        CleanResult result = cleanService.clean(projectDir, BuildSettings.defaults());

        assertEquals(0, result.deletedCount());
    }

    @Test
    void doesNotDeleteGlobalDependencyCache() throws IOException {
        file(".zolt/cache/com/example/app.jar");
        file("target/classes/com/example/Main.class");

        cleanService.clean(projectDir, BuildSettings.defaults());

        assertTrue(Files.exists(projectDir.resolve(".zolt/cache/com/example/app.jar")));
    }

    @Test
    void preservesDeclaredGeneratedRootsByDefault() throws IOException {
        file("target/classes/com/example/Main.class");
        file("target/generated/sources/openapi/com/example/Generated.java");
        BuildSettings settings = BuildSettings.defaults().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false)),
                List.of());

        CleanResult result = cleanService.clean(projectDir, settings);

        assertEquals(1, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve("target/classes")));
        assertTrue(Files.exists(projectDir.resolve("target/generated/sources/openapi/com/example/Generated.java")));
    }

    @Test
    void deletesDeclaredGeneratedRootsWhenCleanIsEnabled() throws IOException {
        file("target/classes/com/example/Main.class");
        file("target/generated/sources/openapi/com/example/Generated.java");
        BuildSettings settings = BuildSettings.defaults().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        true)),
                List.of());

        CleanResult result = cleanService.clean(projectDir, settings);

        assertEquals(1, result.deletedCount());
        assertFalse(Files.exists(projectDir.resolve("target")));
    }
}
