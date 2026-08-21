package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.createSymlink;
import static sh.zolt.build.packaging.PackageServiceTestSupport.currentJavaMajorVersion;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageServiceTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProjectPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceFailureTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void rejectsPackageArchiveNameThatUsesUnsafeProjectName() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> packageService.packageJar(
                        projectDir,
                        config(new ProjectMetadata(
                                "../outside",
                                "0.1.0",
                                "com.example",
                                currentJavaMajorVersion(),
                                Optional.of("com.example.Main"))),
                        projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("[project].name"));
        assertTrue(exception.getMessage().contains("../outside"));
        assertFalse(Files.exists(projectDir.resolve("target/outside-0.1.0.jar")));
    }

    @Test
    void rejectsPackageArchiveWhenTargetParentSymlinkEscapesProject() throws IOException {
        writeLockfile(projectDir);
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-target-");
        createSymlink(projectDir.resolve("target"), outside);
        Path classes = projectDir.resolve("classes/com/example/Main.class");
        Files.createDirectories(classes.getParent());
        Files.write(classes, new byte[] {0});
        BuildResult buildResult = new BuildResult(Optional.empty(), 1, 0, projectDir.resolve("classes"), "");

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> packageService.packageJar(
                        projectDir,
                        config(Optional.of("com.example.Main")),
                        buildResult));

        assertTrue(exception.getMessage().contains("package archive"));
        assertTrue(exception.getMessage().contains("target/demo-0.1.0.jar"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
        assertFalse(Files.exists(outside.resolve("demo-0.1.0.jar")));
    }

    @Test
    void failsBeforeWritingPackageWhenCachedRuntimeJarDoesNotMatchLockfileHash() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path runtimeJar = cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "corrupted runtime jar bytes");
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:runtime-lib"
                version = "1.0.0"
                lane = "runtime"
                resolvedScope = "runtime"

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                jarSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                dependencies = []
                """);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> packageService.packageJar(
                        projectDir,
                        config(Optional.of("com.example.Main")),
                        cacheRoot));

        assertTrue(exception.getMessage().contains(
                "Cached jar integrity check failed for com.example:runtime-lib:1.0.0"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void duplicateJarEntriesFailWithActionableError() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        source(projectDir, "src/main/resources/META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");

        PackageException exception = assertThrows(
                PackageException.class,
                () -> packageService.packageJar(
                        projectDir,
                        config(Optional.of("com.example.Main")),
                        projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("Duplicate jar entry `META-INF/MANIFEST.MF`"));
        assertTrue(exception.getMessage().contains("Remove or rename the duplicate resource"));
    }
}
