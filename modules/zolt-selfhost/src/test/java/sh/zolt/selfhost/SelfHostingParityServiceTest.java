package sh.zolt.selfhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfHostingParityServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void comparesJarEntriesIgnoringManifest() throws IOException {
        writeProject();
        Path bootstrapJar = tempDir.resolve("build/modules/demo.jar");
        Path zoltJar = tempDir.resolve("target/demo-0.1.0.jar");
        writeJar(bootstrapJar, List.of("META-INF/MANIFEST.MF", "com/example/Main.class", "config/app.properties"));
        writeJar(zoltJar, List.of("META-INF/MANIFEST.MF", "com/example/Main.class", "config/app.properties"));
        SelfHostingParityService service = service(zoltJar);

        SelfHostingParityResult result = service.compare(tempDir, tempDir.resolve("cache"), bootstrapJar);

        assertTrue(result.ok());
        assertEquals(bootstrapJar, result.bootstrapJar());
        assertEquals(zoltJar, result.zoltJar());
        assertEquals(Set.of(), result.missingFromZolt());
        assertEquals(Set.of(), result.extraInZolt());
    }

    @Test
    void reportsMissingAndExtraEntries() throws IOException {
        writeProject();
        Path bootstrapJar = tempDir.resolve("build/modules/demo.jar");
        Path zoltJar = tempDir.resolve("target/demo-0.1.0.jar");
        writeJar(bootstrapJar, List.of("com/example/Main.class", "config/expected.properties"));
        writeJar(zoltJar, List.of("com/example/Main.class", "config/extra.properties"));
        SelfHostingParityService service = service(zoltJar);

        SelfHostingParityResult result = service.compare(tempDir, tempDir.resolve("cache"), bootstrapJar);

        assertFalse(result.ok());
        assertEquals(Set.of("config/expected.properties"), result.missingFromZolt());
        assertEquals(Set.of("config/extra.properties"), result.extraInZolt());
    }

    @Test
    void packagesWorkspaceDefaultMemberWhenProjectUsesRealWorkspace() throws IOException {
        writeProject();
        Path bootstrapJar = tempDir.resolve("build/modules/demo.jar");
        Path zoltJar = tempDir.resolve("target/demo-0.1.0.jar");
        Path workspaceClasses = tempDir.resolve("modules/core/target/classes");
        Path runtimeClasspath = tempDir.resolve("target/demo-0.1.0.runtime-classpath");
        writeClass(workspaceClasses.resolve("com/example/Core.class"));
        writeClass(workspaceClasses.resolve("META-INF/services/io.quarkus.test.junit.buildchain.TestBuildChainCustomizerProducer"));
        Files.createDirectories(runtimeClasspath.getParent());
        Files.writeString(runtimeClasspath, workspaceClasses + "\n" + tempDir.resolve("cache/external.jar") + "\n");
        writeJar(bootstrapJar, List.of("com/example/Main.class", "com/example/Core.class"));
        writeJar(zoltJar, List.of("com/example/Main.class"));
        SelfHostingParityService service = new SelfHostingParityService(
                new ManifestProjectConfigLoader(),
                (projectDirectory, config, cacheRoot) -> {
                    throw new AssertionError("real workspace parity should use workspace packaging");
                },
                projectDirectory -> true,
                (projectDirectory, cacheRoot) -> packageResult(zoltJar, Optional.of(runtimeClasspath)));

        SelfHostingParityResult result = service.compare(tempDir, tempDir.resolve("cache"), bootstrapJar);

        assertTrue(result.ok());
        assertEquals(zoltJar, result.zoltJar());
    }

    @Test
    void missingBootstrapJarIsActionable() throws IOException {
        writeProject();
        SelfHostingParityService service = service(tempDir.resolve("target/demo-0.1.0.jar"));

        SelfHostingParityException exception = assertThrows(
                SelfHostingParityException.class,
                () -> service.compare(tempDir, tempDir.resolve("cache"), Path.of("missing.jar")));

        assertTrue(exception.getMessage().contains("Self-hosting parity requires bootstrap jar"));
        assertTrue(exception.getMessage().contains("--bootstrap-jar"));
    }

    private SelfHostingParityService service(Path zoltJar) {
        return new SelfHostingParityService(
                new ManifestProjectConfigLoader(),
                (projectDirectory, config, cacheRoot) -> packageResult(zoltJar),
                projectDirectory -> false,
                (projectDirectory, cacheRoot) -> {
                    throw new AssertionError("single-project parity should not use workspace packaging");
                });
    }

    private static PackageResult packageResult(Path zoltJar) {
        return packageResult(zoltJar, Optional.empty());
    }

    private static PackageResult packageResult(Path zoltJar, Optional<Path> runtimeClasspathPath) {
        return new PackageResult(
                new BuildResult(
                        Optional.empty(),
                        1,
                        0,
                        zoltJar.getParent().resolve("classes"),
                        ""),
                zoltJar,
                runtimeClasspathPath,
                1,
                true);
    }

    private void writeProject() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                """);
    }

    private static void writeJar(Path path, List<String> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (String entry : entries) {
                output.putNextEntry(new JarEntry(entry));
                output.write(new byte[] {0});
                output.closeEntry();
            }
        }
    }

    private static void writeClass(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {0});
    }
}
