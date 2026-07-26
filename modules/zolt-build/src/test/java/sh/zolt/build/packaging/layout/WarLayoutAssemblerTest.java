package sh.zolt.build.packaging.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.build.packaging.PackageRuntimeJars;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.PackageId;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WarLayoutAssemblerTest {
    private final WarLayoutAssembler assembler = new WarLayoutAssembler(new ManifestGenerator());

    @TempDir
    private Path projectDir;

    @Test
    void assemblesWarLayoutWithClassesResourcesAndStoredRuntimeJars() throws IOException {
        Path outputDirectory = projectDir.resolve("target/classes");
        Path mainClass = outputDirectory.resolve("com/example/Main.class");
        Files.createDirectories(mainClass.getParent());
        Files.write(mainClass, new byte[] {1});
        Files.writeString(outputDirectory.resolve("application.properties"), "name=demo\n");
        Files.writeString(outputDirectory.resolve(".zolt-incremental-main.state"), "state\n");
        Path runtimeJar = projectDir.resolve("cache/com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        createJarWithEntry(runtimeJar, "com/example/runtime/RuntimeLib.class");
        Path warPath = projectDir.resolve("target/demo-0.1.0.war");

        PackageRuntimeJar runtimeInput =
                new PackageRuntimeJar(new PackageId("com.example", "runtime-lib"), "1.0.0", runtimeJar);
        PackageResult result = assembler.assemble(
                projectDir,
                config(),
                new BuildResult(Optional.empty(), 1, 1, outputDirectory, ""),
                outputDirectory,
                warPath,
                List.of(runtimeInput));

        assertEquals(PackageMode.WAR, result.mode());
        assertEquals(warPath, result.jarPath());
        assertEquals(Optional.empty(), result.runtimeClasspathPath());
        assertEquals(2, result.entryCount());
        assertFalse(result.hasMainClass());
        try (JarFile jar = new JarFile(warPath.toFile())) {
            assertNotNull(jar.getEntry("META-INF/MANIFEST.MF"));
            assertFalse(jar.getManifest().getMainAttributes().containsKey(Attributes.Name.MAIN_CLASS));
            assertNotNull(jar.getEntry("WEB-INF/"));
            assertNotNull(jar.getEntry("WEB-INF/classes/"));
            assertNotNull(jar.getEntry("WEB-INF/lib/"));
            assertNotNull(jar.getEntry("WEB-INF/classes/com/example/Main.class"));
            assertNotNull(jar.getEntry("WEB-INF/classes/application.properties"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(".zolt-incremental-main.state")));
            JarEntry runtimeEntry =
                    jar.getJarEntry("WEB-INF/lib/" + PackageRuntimeJars.nestedJarName(runtimeInput));
            assertNotNull(runtimeEntry);
            assertEquals(JarEntry.STORED, runtimeEntry.getMethod());
        }
    }

    @Test
    void reportsMissingRuntimeJarWithResolveGuidance() throws IOException {
        Path outputDirectory = projectDir.resolve("target/classes");
        Files.createDirectories(outputDirectory);
        Path missingJar = projectDir.resolve("cache/com/example/missing/1.0.0/missing-1.0.0.jar");

        PackageException exception = assertThrows(
                PackageException.class,
                () -> assembler.assemble(
                        projectDir,
                        config(),
                        new BuildResult(Optional.empty(), 0, 0, outputDirectory, ""),
                        outputDirectory,
                        projectDir.resolve("target/demo-0.1.0.war"),
                        List.of(new PackageRuntimeJar(new PackageId("com.example", "missing"), "1.0.0", missingJar))));

        assertEquals(
                "Runtime dependency jar for com.example:missing is missing at "
                        + missingJar
                        + ". Run `zolt resolve` to refresh the artifact cache and retry.",
                exception.getMessage());
    }

    private ProjectConfig config() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"
                """);
    }

    private static void createJarWithEntry(Path jarPath, String entryName) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            JarEntry entry = new JarEntry(entryName);
            jar.putNextEntry(entry);
            jar.write(new byte[] {1});
            jar.closeEntry();
        }
    }
}
