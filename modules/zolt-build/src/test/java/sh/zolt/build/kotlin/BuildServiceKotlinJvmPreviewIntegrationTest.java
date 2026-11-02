package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.JavacException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real Kotlin and javac proof for preview-marked JVM classes. */
final class BuildServiceKotlinJvmPreviewIntegrationTest {
    private static final String MAIN_CLASS = "com.example.PreviewApp";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void marksKotlinClassesPreviewAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts = prepare();
        writeKotlinSource();
        writeJavaSource(false);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        byte[] baselineClass = kotlinClassBytes();
        assertClassVersion(baselineClass, 0, 65);
        assertEquals("plain-7", run(artifacts, false));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult preview = build(service, true);
        assertFalse(preview.mainCompilationSkipped());
        byte[] previewClass = kotlinClassBytes();
        assertClassVersion(previewClass, 0xffff, 65);
        assertFalse(Arrays.equals(baselineClass, previewClass));
        JavaRunException disabled = assertThrows(
                JavaRunException.class,
                () -> run(artifacts, false));
        assertTrue(disabled.getMessage().contains("Preview features are not enabled"));
        assertEquals("plain-7", run(artifacts, true));

        BuildResult previewWarm = build(service, true);
        assertTrue(previewWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertArrayEquals(baselineClass, kotlinClassBytes());
        assertEquals("plain-7", run(artifacts, false));
    }

    @Test
    void enablesJavaPreviewSyntaxInMixedSources() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts = prepare();
        writeKotlinSource();
        writeJavaSource(true);
        BuildService service = new BuildService();

        JavacException disabled = assertThrows(
                JavacException.class,
                () -> build(service, false));
        assertTrue(disabled.getMessage().contains("preview feature"), disabled.getMessage());

        BuildResult preview = build(service, true);
        assertFalse(preview.mainCompilationSkipped());
        assertClassVersion(kotlinClassBytes(), 0xffff, 65);
        assertClassVersion(javaClassBytes(), 0xffff, 65);
        assertEquals("preview-7", run(artifacts, true));

        BuildResult previewWarm = build(service, true);
        assertTrue(previewWarm.mainCompilationSkipped());
    }

    private KotlinCompilerIntegrationArtifacts.Prepared prepare() throws Exception {
        return KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
    }

    private BuildResult build(BuildService service, boolean preview) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(preview),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private String run(
            KotlinCompilerIntegrationArtifacts.Prepared artifacts,
            boolean preview) {
        List<Path> classpath = new ArrayList<>();
        classpath.add(projectDir.resolve("target/classes"));
        classpath.addAll(artifacts.applicationClasspath());
        JavaRunResult result = new JavaRunner().run(
                Path.of(System.getProperty("java.home"), "bin", "java"),
                new Classpath(classpath),
                MAIN_CLASS,
                preview ? List.of("--enable-preview") : List.of(),
                List.of());
        return result.output().strip();
    }

    private void writeKotlinSource() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/PreviewApp.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object PreviewApp {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(JavaPreview.value(7))
                    }
                }
                """);
    }

    private void writeJavaSource(boolean preview) throws Exception {
        Path source = projectDir.resolve("src/main/java/com/example/JavaPreview.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, preview
                ? """
                        package com.example;

                        import static java.lang.StringTemplate.STR;

                        public final class JavaPreview {
                            private JavaPreview() {}

                            public static String value(int value) {
                                return STR."preview-\\{value}";
                            }
                        }
                        """
                : """
                        package com.example;

                        public final class JavaPreview {
                            private JavaPreview() {}

                            public static String value(int value) {
                                return "plain-" + value;
                            }
                        }
                        """);
    }

    private byte[] kotlinClassBytes() throws Exception {
        return Files.readAllBytes(classFile("PreviewApp.class"));
    }

    private byte[] javaClassBytes() throws Exception {
        return Files.readAllBytes(classFile("JavaPreview.class"));
    }

    private Path classFile(String fileName) {
        return projectDir.resolve("target/classes/com/example").resolve(fileName);
    }

    private static void assertClassVersion(
            byte[] classBytes,
            int expectedMinor,
            int expectedMajor) {
        assertTrue(classBytes.length >= 8);
        assertEquals(0xca, classBytes[0] & 0xff);
        assertEquals(0xfe, classBytes[1] & 0xff);
        assertEquals(0xba, classBytes[2] & 0xff);
        assertEquals(0xbe, classBytes[3] & 0xff);
        assertEquals(expectedMinor, unsignedShort(classBytes, 4));
        assertEquals(expectedMajor, unsignedShort(classBytes, 6));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 8 | bytes[offset + 1] & 0xff;
    }

    private static ProjectConfig config(boolean preview) {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xjvm-enable-preview\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-jvm-preview"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.PreviewApp"

                [build]
                sources = ["src/main/kotlin", "src/main/java"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = [%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(compilerArguments));
    }
}
