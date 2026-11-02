package sh.zolt.build.kotlin.compatibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class BuildServiceKotlinCompilerCompatibilityTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void rejectsUnsupportedMainOptionBeforeResolutionCacheReuseOrOutputMutation() throws IOException {
        Path classFile = write("target/classes/com/example/Existing.class", new byte[] {1, 2, 3});
        Path fingerprint = write("target/classes/.zolt-build-main.fingerprint", new byte[] {4, 5, 6});

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> new BuildService().buildWithClasspaths(
                        projectDir,
                        config(),
                        cacheRoot,
                        true));

        assertTrue(failure.getMessage().contains("[compiler].args"), failure.getMessage());
        assertTrue(failure.getMessage().contains("1.9.0"), failure.getMessage());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(classFile));
        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(fingerprint));
    }

    private Path write(String relativePath, byte[] bytes) throws IOException {
        Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "old-kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "1.9.0"

                [compiler]
                args = ["-Xannotation-default-target=param-property"]
                """);
    }
}
