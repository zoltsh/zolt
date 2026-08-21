package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [native].output} is relative to {@code [build.output].root} (design §12.5), so every
 * ownership fixture here places the native namespace by moving the output root rather than by
 * authoring an escaping native path.
 */
final class NativeOutputPlanTest {
    @TempDir
    private Path project;

    @Test
    void plansTheCompleteDefaultNativeNamespace() {
        NativeOutputPlan plan = NativeOutputPlan.plan(project, config("jar", "native", "demo"));

        assertEquals(project.resolve("target/native/demo"), plan.binary());
        assertEquals(project.resolve("target/native/native-image.log"), plan.log());
        assertEquals(project.resolve("target/native/spring-aot-evidence.json"), plan.evidence());
        assertEquals(project.resolve("target/native/input"), plan.inputDirectory());
        assertTrue(plan.configuredPackageOutputs().contains(project.resolve("target/demo-0.1.0.jar")));
        assertTrue(plan.configuredPackageOutputs().contains(project.resolve("target/demo-0.1.0.runtime-classpath")));
        assertTrue(plan.configuredPackageOutputs().contains(
                project.resolve("target/demo-0.1.0.jar.zolt-package.json")));
        assertTrue(plan.configuredPackageOutputs().contains(project.resolve("target/demo-0.1.0-sources.jar")));
        assertTrue(plan.configuredPackageOutputs().contains(project.resolve("target/demo-0.1.0-javadoc.jar")));
        assertTrue(plan.configuredPackageOutputs().contains(project.resolve("target/demo-0.1.0-tests.jar")));
        assertTrue(plan.configuredPackageOutputs().contains(project.resolve("target/publish/demo-0.1.0.pom")));
    }

    @Test
    void rejectsEveryReservedNativeName() {
        for (String imageName : List.of("native-image.log", "spring-aot-evidence.json", "input")) {
            NativeImageException exception = assertThrows(
                    NativeImageException.class,
                    () -> NativeOutputPlan.plan(project, config("jar", "native", imageName)),
                    imageName);
            assertTrue(exception.getMessage().contains("Native output ownership conflict"), exception.getMessage());
        }
    }

    @Test
    void rejectsANativeBinaryThatIsAConfiguredPublicationOutput() {
        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("jar", "publish", "demo-0.1.0.pom")));

        assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    @Test
    void rejectsConfiguredOutputsNestedBelowTheNativeOutputDirectory() {
        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("jar", "publish", "demo")));

        assertTrue(exception.getMessage().contains("native output directory"), exception.getMessage());
        assertTrue(exception.getMessage().contains("publication output"), exception.getMessage());
    }

    @Test
    void resolvesSymlinkAncestorsBeforeComparingOwnership() throws IOException {
        Files.createDirectories(project.resolve("target"));
        Files.createSymbolicLink(project.resolve("target/native-link"), Path.of("."));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(
                        project,
                        config("uber-jar", "native-link", "demo-0.1.0.jar")));

        assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    @Test
    void rejectsANativeNamespaceNestedInsideAConfiguredPackageOutput() {
        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("jar", "demo-0.1.0.jar", "demo")));

        assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    private static ProjectConfig config(String mode, String output, String imageName) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [package]
                mode = "%s"
                sources = true
                javadoc = true
                testJar = true

                [native]
                output = "%s"
                name = "%s"
                """.formatted(mode, output, imageName));
    }
}
