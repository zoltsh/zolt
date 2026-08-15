package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeOutputPlanTest {
    @TempDir
    private Path project;

    @Test
    void plansTheCompleteDefaultNativeNamespace() {
        NativeOutputPlan plan = NativeOutputPlan.plan(project, config("thin", "target/native", "demo"));

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
                    () -> NativeOutputPlan.plan(project, config("thin", "target/native", imageName)),
                    imageName);
            assertTrue(exception.getMessage().contains("Native output ownership conflict"), exception.getMessage());
        }
    }

    @Test
    void rejectsConfiguredArchiveEvidenceSidecarSupplementalAndPublicationCollisions() {
        for (Collision collision : List.of(
                new Collision("uber", "target", "demo-0.1.0.jar"),
                new Collision("spring-boot-war", "target", "demo-0.1.0.war"),
                new Collision("thin", "target", "demo-0.1.0.jar.zolt-package.json"),
                new Collision("thin", "target", "demo-0.1.0.runtime-classpath"),
                new Collision("thin", "target", "demo-0.1.0-sources.jar"),
                new Collision("thin", "target/publish", "demo-0.1.0.pom"))) {
            NativeImageException exception = assertThrows(
                    NativeImageException.class,
                    () -> NativeOutputPlan.plan(
                            project,
                            config(collision.mode(), collision.output(), collision.imageName())),
                    collision.toString());
            assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
        }
    }

    @Test
    void rejectsConfiguredOutputsNestedBelowTheNativeBinaryPath() {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [build]
                outputRoot = "target/native/demo"

                [native]
                output = "target/native"
                imageName = "demo"
                """);

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config));

        assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    @Test
    void resolvesSymlinkAncestorsBeforeComparingOwnership() throws IOException {
        Files.createDirectories(project.resolve("target"));
        Files.createSymbolicLink(project.resolve("native-link"), Path.of("target"));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(
                        project,
                        config("uber", "native-link", "demo-0.1.0.jar")));

        assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    @Test
    void rejectsConfiguredOutputsInsidePrivateNativeInput() {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [build]
                outputRoot = "target/native/input"

                [native]
                output = "target/native"
                imageName = "demo"
                """);

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config));

        assertTrue(exception.getMessage().contains("native package input"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    private static ProjectConfig config(String mode, String output, String imageName) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [package]
                mode = "%s"
                sources = true
                javadoc = true
                tests = true

                [native]
                output = "%s"
                imageName = "%s"
                """.formatted(mode, output, imageName));
    }

    private record Collision(String mode, String output, String imageName) {
    }
}
