package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.packaging.layout.UberJarLayoutAssembler;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UberJarWorkspaceDirectoryTest {
    @TempDir
    Path root;

    @Test
    void workspaceDirectoriesUseTheExternalJarMergePolicy() throws IOException {
        Path application = root.resolve("target/classes");
        Files.createDirectories(application);
        source(application, "example/Main.class", "main");
        Path first = dependency("first");
        Path second = dependency("second");
        Path archive = root.resolve("target/demo.jar");
        PackageResult result = new UberJarLayoutAssembler(new ManifestGenerator()).assemble(
                root,
                config(Optional.of("example.Main")).withPackageSettings(new PackageSettings(PackageMode.UBER)),
                new BuildResult(Optional.empty(), 1, 0, application, ""),
                application,
                archive,
                List.of(runtime("first", first), runtime("second", second)));

        try (JarFile jar = new JarFile(archive.toFile())) {
            assertNull(jar.getEntry("module-info.class"));
            assertNull(jar.getEntry("META-INF/versions/9/module-info.class"));
            assertNull(jar.getEntry("META-INF/LIB.RSA"));
            assertNull(jar.getEntry("META-INF/INDEX.LIST"));
            assertEquals("example.Main", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getEntry("example/first/Library.class"));
            assertNotNull(jar.getEntry("example/second/Library.class"));
            assertNotNull(jar.getEntry("META-INF/zolt-uber/example/first/1.0.0/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/zolt-uber/example/second/1.0.0/LICENSE"));
        }
        assertEquals(4, result.mergeDecisions().stream()
                .filter(decision -> decision.kind().equals("omitted-module-descriptor")).count());
        assertEquals(2, result.mergeDecisions().stream()
                .filter(decision -> decision.kind().equals("relocated-metadata")).count());
        assertTrue(result.mergeDecisions().stream()
                .anyMatch(decision -> decision.sources().contains("example:first")));
    }

    private Path dependency(String name) throws IOException {
        Path output = root.resolve(name + "/target/classes");
        source(output, "example/" + name + "/Library.class", name);
        source(output, "module-info.class", "module");
        source(output, "META-INF/versions/9/module-info.class", "module9");
        source(output, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: wrong.Main\n");
        source(output, "META-INF/LIB.RSA", "signature");
        source(output, "META-INF/INDEX.LIST", "index");
        source(output, "META-INF/LICENSE", name + " license");
        return output;
    }

    private static PackageRuntimeJar runtime(String name, Path output) {
        return new PackageRuntimeJar(new PackageId("example", name), "1.0.0", output);
    }
}
