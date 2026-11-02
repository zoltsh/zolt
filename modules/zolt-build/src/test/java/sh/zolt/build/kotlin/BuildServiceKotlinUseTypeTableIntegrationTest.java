package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Real compiler proof for Kotlin metadata type-table serialization. */
final class BuildServiceKotlinUseTypeTableIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void serializesMetadataTypeTablesAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals(4, invoke(artifacts.applicationClasspath()));
        OutputBytes baselineBytes = outputBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult typeTable = build(service, true);
        assertFalse(typeTable.mainCompilationSkipped());
        assertEquals(4, invoke(artifacts.applicationClasspath()));
        OutputBytes typeTableBytes = outputBytes();
        assertFalse(Arrays.equals(baselineBytes.box(), typeTableBytes.box()));
        assertFalse(Arrays.equals(baselineBytes.api(), typeTableBytes.api()));

        BuildResult typeTableWarm = build(service, true);
        assertTrue(typeTableWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(4, invoke(artifacts.applicationClasspath()));
        OutputBytes restoredBytes = outputBytes();
        assertArrayEquals(baselineBytes.box(), restoredBytes.box());
        assertArrayEquals(baselineBytes.api(), restoredBytes.api());
    }

    private BuildResult build(BuildService service, boolean useTypeTable) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(useTypeTable),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/TypeTable.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                class TypeTableBox<T : CharSequence>(private val value: T) {
                    fun value(): T = value
                }

                object TypeTableApi {
                    @JvmStatic
                    fun length(): Int = TypeTableBox("zolt").value().length
                }
                """);
    }

    private Object invoke(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.TypeTableApi", true, loader)
                    .getMethod("length")
                    .invoke(null);
        }
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("TypeTableBox.class")),
                Files.readAllBytes(output.resolve("TypeTableApi.class")));
    }

    private static ProjectConfig config(boolean useTypeTable) {
        String compilerArguments = useTypeTable
                ? "\"-parameters\", \"-Xuse-type-table\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-metadata-type-tables"
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

    private record OutputBytes(byte[] box, byte[] api) {}
}
