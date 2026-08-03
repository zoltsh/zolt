package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageRuntimeJarMaterializerTest {
    @TempDir
    private Path projectDir;

    @Test
    void regeneratesModifiedTruncatedAndDeletedCachedJarFromChecksumManifest()
            throws IOException {
        Path providerOutput = projectDir.resolve("provider/target/classes");
        Files.createDirectories(providerOutput.resolve("com/example"));
        Files.writeString(
                providerOutput.resolve("com/example/Provider.class"),
                "provider-bytecode");
        ProjectConfig config = config(Optional.of("com.example.Main"));
        PackageRuntimeJar input = new PackageRuntimeJar(
                new PackageId("com.example", "provider"),
                "1.0.0",
                providerOutput);
        PackageRuntimeJarMaterializer materializer =
                new PackageRuntimeJarMaterializer();

        PackageMaterializedInput original = materializer
                .materialize(projectDir, config, List.of(input))
                .materializedInputs()
                .getFirst();
        assertEquals(
                PackageRuntimeJars.nestedJarName(input),
                original.jarPath().getFileName().toString());
        byte[] expected = Files.readAllBytes(original.jarPath());
        Path cacheManifest = original.jarPath().resolveSibling(
                original.jarPath().getFileName() + ".zolt-cache");
        String metadata = Files.readString(cacheManifest);
        assertEquals(5L, metadata.lines().count());
        assertTrue(metadata.contains(
                "schema=zolt.package-runtime-input-cache.v2"));
        assertTrue(metadata.contains(
                "identity=" + input.artifactIdentity().canonicalKey()));
        assertTrue(metadata.contains(
                "nestedName=" + PackageRuntimeJars.nestedJarName(input)));
        assertTrue(metadata.contains(
                "sourceFingerprint=" + original.sourceFingerprint()));
        assertTrue(metadata.contains("jarSha256=" + original.sha256()));

        try (PackageArchiveWriter archive =
                PackageArchiveWriter.open(original.jarPath())) {
            archive.writeEntry("corrupt.class", new byte[] {1, 2, 3});
            archive.commit();
        }
        assertRegenerated(materializer, config, input, expected);

        Files.write(original.jarPath(), new byte[] {0x50, 0x4b});
        assertRegenerated(materializer, config, input, expected);

        Files.delete(original.jarPath());
        assertRegenerated(materializer, config, input, expected);

        try (var stream = Files.list(original.jarPath().getParent())) {
            assertFalse(stream.anyMatch(path ->
                    path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private void assertRegenerated(
            PackageRuntimeJarMaterializer materializer,
            ProjectConfig config,
            PackageRuntimeJar input,
            byte[] expected) throws IOException {
        PackageMaterializedInput regenerated = materializer
                .materialize(projectDir, config, List.of(input))
                .materializedInputs()
                .getFirst();
        assertEquals(
                PackageRuntimeJars.nestedJarName(input),
                regenerated.jarPath().getFileName().toString());
        assertEquals(
                java.util.HexFormat.of().formatHex(expected),
                java.util.HexFormat.of().formatHex(
                        Files.readAllBytes(regenerated.jarPath())));
        try (JarFile jar = new JarFile(regenerated.jarPath().toFile())) {
            assertTrue(jar.getEntry("com/example/Provider.class") != null);
        }
    }
}
