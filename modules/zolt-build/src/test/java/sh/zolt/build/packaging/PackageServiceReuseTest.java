package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.buildSettingsWithMetadata;
import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageServiceTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceReuseTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void reusesVerifiedPackageEvidenceAndRebuildsChangedInputs() throws IOException {
        ProjectConfig config = project();
        PackageResult first = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));
        FileTime marker = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(first.jarPath(), marker);

        PackageResult reused = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));

        assertTrue(reused.packagingReused());
        assertEquals(marker, Files.getLastModifiedTime(first.jarPath()));
        assertEquals(first.entryCount(), reused.entryCount());

        source(projectDir, "src/main/resources/application.properties", "name=changed\n");
        PackageResult rebuilt = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));

        assertFalse(rebuilt.packagingReused());
        assertFalse(marker.equals(Files.getLastModifiedTime(first.jarPath())));
    }

    @Test
    void reproduciblePackageBytesIgnoreInputTimestamps() throws IOException {
        ProjectConfig config = project().withBuildSettings(buildSettingsWithMetadata(
                new BuildMetadataSettings(false, false, true)));
        PackageResult first = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));
        byte[] expected = Files.readAllBytes(first.jarPath());
        Files.delete(first.evidenceManifestPath().orElseThrow());
        Files.setLastModifiedTime(
                projectDir.resolve("src/main/java/com/example/Main.java"),
                FileTime.fromMillis(2_000_000L));
        Files.setLastModifiedTime(
                projectDir.resolve("target/classes/com/example/Main.class"),
                FileTime.fromMillis(3_000_000L));

        PackageResult rebuilt = packageService.packageJar(
                projectDir,
                config,
                projectDir.resolve("cache"));

        assertFalse(rebuilt.packagingReused());
        assertArrayEquals(expected, Files.readAllBytes(rebuilt.jarPath()));
    }

    private ProjectConfig project() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        source(projectDir, "src/main/resources/application.properties", "name=demo\n");
        return config(Optional.of("com.example.Main"));
    }
}
