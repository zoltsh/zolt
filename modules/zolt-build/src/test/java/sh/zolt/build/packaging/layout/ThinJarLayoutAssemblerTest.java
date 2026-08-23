package sh.zolt.build.packaging.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ThinJarLayoutAssemblerTest {
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

    private final ThinJarLayoutAssembler assembler = new ThinJarLayoutAssembler(
            new ManifestGenerator(),
            new ZoltLockfileReader(),
            new ClasspathBuilder());

    @TempDir
    private Path projectDir;

    @Test
    void assemblesThinJarAndRuntimeClasspathFromPrecomputedPackages() throws IOException {
        Path outputDirectory = projectDir.resolve("target/classes");
        Path mainClass = outputDirectory.resolve("com/example/Main.class");
        Files.createDirectories(mainClass.getParent());
        Files.write(mainClass, new byte[] {1});
        Files.writeString(outputDirectory.resolve(".zolt-incremental-main.state"), "state\n");
        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");

        PackageResult result = assembler.assemble(
                projectDir,
                projectDir.resolve("zolt.lock"),
                config(),
                new BuildResult(Optional.empty(), 1, 0, outputDirectory, ""),
                jarPath,
                Optional.of(projectDir.resolve("cache")),
                Optional.of(List.of(
                        packageWithScope("com.example", "runtime-lib", DependencyScope.RUNTIME),
                        packageWithScope("com.example", "provided-api", DependencyScope.PROVIDED))),
                Optional.empty());

        assertEquals(PackageMode.THIN, result.mode());
        assertEquals(jarPath, result.jarPath());
        assertEquals(Optional.of(projectDir.resolve("target/demo-0.1.0.runtime-classpath")), result.runtimeClasspathPath());
        assertEquals(1, result.entryCount());
        assertEquals(
                projectDir.resolve("cache/com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar") + "\n",
                Files.readString(result.runtimeClasspathPath().orElseThrow()));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("META-INF/MANIFEST.MF"));
            assertNotNull(jar.getEntry("com/example/Main.class"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(".zolt-incremental-main.state")));
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("1.0", attributes.getValue(Attributes.Name.MANIFEST_VERSION));
            assertEquals("com.example.Main", attributes.getValue(Attributes.Name.MAIN_CLASS));
        }
    }

    @Test
    void reproducibleThinJarCarriesProvenanceAndIsByteStable() throws IOException {
        writeGitMetadata(projectDir, COMMIT_SHA);
        Path outputDirectory = projectDir.resolve("target/classes");
        Path mainClass = outputDirectory.resolve("com/example/Main.class");
        Files.createDirectories(mainClass.getParent());
        Files.write(mainClass, new byte[] {1});
        ProjectConfig config = config(true);
        Path firstJar = projectDir.resolve("target/demo-0.1.0-first.jar");
        Path secondJar = projectDir.resolve("target/demo-0.1.0-second.jar");

        assembler.assemble(
                projectDir,
                projectDir.resolve("zolt.lock"),
                config,
                new BuildResult(Optional.empty(), 1, 0, outputDirectory, ""),
                firstJar,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assembler.assemble(
                projectDir,
                projectDir.resolve("zolt.lock"),
                config,
                new BuildResult(Optional.empty(), 1, 0, outputDirectory, ""),
                secondJar,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertArrayEquals(Files.readAllBytes(firstJar), Files.readAllBytes(secondJar));
        try (JarFile jar = new JarFile(firstJar.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("0.1.0", attributes.getValue("Implementation-Version"));
            assertEquals(COMMIT_SHA, attributes.getValue("SCM-Revision"));
            assertEquals("1970-01-01T00:00:00Z", attributes.getValue("Build-Timestamp"));
            assertNotNull(attributes.getValue("Build-Jdk"));
        }
    }

    private ProjectConfig config() {
        return config(false);
    }

    private ProjectConfig config(boolean reproducible) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [build.metadata]
                reproducible = %s
                """.formatted(reproducible));
    }

    private ResolvedClasspathPackage packageWithScope(String groupId, String artifactId, DependencyScope scope) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId(groupId, artifactId),
                        "1.0.0",
                        false,
                        projectDir.resolve("cache/%s/%s/1.0.0/%s-1.0.0.pom".formatted(
                                groupId.replace('.', '/'),
                                artifactId,
                                artifactId)),
                        projectDir.resolve("cache/%s/%s/1.0.0/%s-1.0.0.jar".formatted(
                                groupId.replace('.', '/'),
                                artifactId,
                        artifactId))),
                scope);
    }

    private static void writeGitMetadata(Path projectDir, String sha) throws IOException {
        Path head = projectDir.resolve(".git/HEAD");
        Path branch = projectDir.resolve(".git/refs/heads/main");
        Files.createDirectories(branch.getParent());
        Files.writeString(head, "ref: refs/heads/main\n");
        Files.writeString(branch, sha + "\n");
    }
}
