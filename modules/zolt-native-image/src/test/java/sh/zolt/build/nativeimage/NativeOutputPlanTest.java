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
 *
 * <p>The collision matrix proves each configured package output family — archive, war archive,
 * evidence sidecar, supplemental runtime classpath, classifier jar, and publication POM — is owned
 * independently. The private staging fixtures use filesystem aliases because {@code [native].output}
 * can only name a directory strictly below the output root, so nothing authored can place a package
 * output inside the staging directory.
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
        for (Reserved reserved : List.of(
                new Reserved("native-image.log", "native log"),
                new Reserved("spring-aot-evidence.json", "Spring Boot native evidence"),
                new Reserved("input", "native package input"))) {
            NativeImageException exception = assertThrows(
                    NativeImageException.class,
                    () -> NativeOutputPlan.plan(project, config("jar", "native", reserved.imageName())),
                    reserved.imageName());
            assertTrue(exception.getMessage().contains("Native output ownership conflict"), exception.getMessage());
            assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
            assertTrue(exception.getMessage().contains(reserved.managedKind()), exception.getMessage());
        }
    }

    @Test
    void rejectsConfiguredArchiveEvidenceSidecarSupplementalAndPublicationCollisions() {
        for (Collision collision : List.of(
                new Collision("uber-jar", "demo-0.1.0.jar", "demo", "target/demo-0.1.0.jar"),
                new Collision("spring-boot-war", "demo-0.1.0.war", "demo", "target/demo-0.1.0.war"),
                new Collision(
                        "jar",
                        "demo-0.1.0.jar.zolt-package.json",
                        "demo",
                        "target/demo-0.1.0.jar.zolt-package.json"),
                new Collision(
                        "jar",
                        "demo-0.1.0.runtime-classpath",
                        "demo",
                        "target/demo-0.1.0.runtime-classpath"),
                new Collision("jar", "demo-0.1.0-sources.jar", "demo", "target/demo-0.1.0-sources.jar"),
                new Collision("jar", "publish", "demo-0.1.0.pom", "target/publish/demo-0.1.0.pom"))) {
            NativeImageException exception = assertThrows(
                    NativeImageException.class,
                    () -> NativeOutputPlan.plan(
                            project,
                            config(collision.mode(), collision.output(), collision.imageName())),
                    collision.toString());

            assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
            assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
            assertTrue(
                    exception.getMessage().contains(project.resolve(collision.owned()).toString()),
                    exception.getMessage());
        }
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

    @Test
    void rejectsConfiguredOutputsInsidePrivateNativeInput() throws IOException {
        Files.createDirectories(project.resolve("target/publish"));
        Files.createDirectories(project.resolve("target/native"));
        Files.createSymbolicLink(project.resolve("target/native/input"), Path.of("..", "publish"));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("jar", "native", "demo")));

        assertTrue(exception.getMessage().contains("native package input"), exception.getMessage());
        assertTrue(exception.getMessage().contains("configured package output"), exception.getMessage());
    }

    @Test
    void rejectsANativeBinaryAliasedInsidePrivateNativeInput() throws IOException {
        Files.createDirectories(project.resolve("target/native"));
        Files.createSymbolicLink(project.resolve("target/native/input"), Path.of(".."));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("jar", "native", "demo")));

        assertTrue(exception.getMessage().contains("native binary"), exception.getMessage());
        assertTrue(exception.getMessage().contains("native package input"), exception.getMessage());
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

    private record Reserved(String imageName, String managedKind) {
    }

    private record Collision(String mode, String output, String imageName, String owned) {
    }
}
