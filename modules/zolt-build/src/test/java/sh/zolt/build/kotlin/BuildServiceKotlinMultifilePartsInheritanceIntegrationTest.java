package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin multifile-part inheritance. */
final class BuildServiceKotlinMultifilePartsInheritanceIntegrationTest {
    private static final String FACADE = "com.example.Utilities";
    private static final String PART_ONE = "com.example.Utilities__PartOneKt";
    private static final String PART_TWO = "com.example.Utilities__PartTwoKt";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesMultifileHierarchyAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        sources();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertLayout(artifacts.applicationClasspath(), false);
        OutputBytes baselineBytes = outputBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult inherited = build(service, true);
        assertFalse(inherited.mainCompilationSkipped());
        assertLayout(artifacts.applicationClasspath(), true);
        OutputBytes inheritedBytes = outputBytes();
        assertFalse(Arrays.equals(baselineBytes.facade(), inheritedBytes.facade()));
        assertFalse(Arrays.equals(baselineBytes.partOne(), inheritedBytes.partOne()));
        assertFalse(Arrays.equals(baselineBytes.partTwo(), inheritedBytes.partTwo()));

        BuildResult inheritedWarm = build(service, true);
        assertTrue(inheritedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertLayout(artifacts.applicationClasspath(), false);
        OutputBytes restoredBytes = outputBytes();
        assertArrayEquals(baselineBytes.facade(), restoredBytes.facade());
        assertArrayEquals(baselineBytes.partOne(), restoredBytes.partOne());
        assertArrayEquals(baselineBytes.partTwo(), restoredBytes.partTwo());
    }

    private BuildResult build(BuildService service, boolean inheritParts) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(inheritParts),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void sources() throws Exception {
        Path sourceRoot = projectDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("PartOne.kt"), """
                @file:JvmName("Utilities")
                @file:JvmMultifileClass

                package com.example

                fun first(): String = "first"
                """);
        Files.writeString(sourceRoot.resolve("PartTwo.kt"), """
                @file:JvmName("Utilities")
                @file:JvmMultifileClass

                package com.example

                fun second(): String = "second"
                """);
    }

    private void assertLayout(
            List<Path> applicationClasspath,
            boolean inheritedParts) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> facade = Class.forName(FACADE, true, loader);
            Class<?> partOne = Class.forName(PART_ONE, true, loader);
            Class<?> partTwo = Class.forName(PART_TWO, true, loader);

            assertEquals("first", invokeFacadeMethod(facade, "first"));
            assertEquals("second", invokeFacadeMethod(facade, "second"));
            if (inheritedParts) {
                assertEquals(partTwo, facade.getSuperclass());
                assertEquals(partOne, partTwo.getSuperclass());
                assertEquals(Object.class, partOne.getSuperclass());
                assertThrows(NoSuchMethodException.class, () -> facade.getDeclaredMethod("first"));
                assertThrows(NoSuchMethodException.class, () -> facade.getDeclaredMethod("second"));
            } else {
                assertEquals(Object.class, facade.getSuperclass());
                assertEquals(Object.class, partOne.getSuperclass());
                assertEquals(Object.class, partTwo.getSuperclass());
                assertEquals("first", facade.getDeclaredMethod("first").invoke(null));
                assertEquals("second", facade.getDeclaredMethod("second").invoke(null));
            }
        }
    }

    private static Object invokeFacadeMethod(Class<?> facade, String methodName) throws Exception {
        Method method = facade.getMethod(methodName);
        assertTrue(method.trySetAccessible());
        return method.invoke(null);
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("Utilities.class")),
                Files.readAllBytes(output.resolve("Utilities__PartOneKt.class")),
                Files.readAllBytes(output.resolve("Utilities__PartTwoKt.class")));
    }

    private static ProjectConfig config(boolean inheritParts) {
        String compilerArguments = inheritParts
                ? "\"-parameters\", \"-Xmultifile-parts-inherit\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-multifile-parts-inheritance"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = [%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(compilerArguments));
    }

    private record OutputBytes(byte[] facade, byte[] partOne, byte[] partTwo) {}
}
