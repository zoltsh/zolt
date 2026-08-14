package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the isolating annotation-processor incremental fast path, driven through the
 * real javac worker (compiled from source and enabled for the class) so generated-output attribution
 * is actually captured.
 */
final class BuildServiceProcessorIncrementalTest {
    private static WorkerAttributionTestSupport worker;

    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @BeforeAll
    static void enableWorker(@TempDir Path workerWork) throws Exception {
        worker = WorkerAttributionTestSupport.enable(workerWork);
    }

    @AfterAll
    static void disableWorker() {
        if (worker != null) {
            worker.close();
        }
    }

    @Test
    void editingAnnotatedSourceRecompilesIncrementallyAndRegeneratesItsOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingProcessorJar(projectDir.resolve("processor-work")));

        BuildResult first = buildService.build(projectDir, config(), cache());
        assertEquals("full", first.mainCompilationMode());
        assertTrue(Files.exists(generatedSource("Widget")));
        assertTrue(Files.exists(generatedSource("Gadget")));
        IncrementalCompileState state = readState();
        assertTrue(state.processorAttributionComplete());

        source("src/main/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("incremental", second.mainCompilationMode());
        assertEquals("", second.mainIncrementalFallbackReason());
        assertTrue(Files.exists(generatedSource("Widget")));
        assertTrue(Files.exists(generatedClass("Widget")));
    }

    @Test
    void editingUnannotatedSourcePreservesUntouchedGeneratedOutputs() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingProcessorJar(projectDir.resolve("processor-work")));

        assertEquals("full", buildService.build(projectDir, config(), cache()).mainCompilationMode());
        Path widgetMeta = generatedSource("Widget");
        byte[] before = Files.readAllBytes(widgetMeta);

        source("src/main/java/com/example/Plain.java", """
                package com.example;

                public final class Plain {
                    public static int version() {
                        return 2;
                    }
                }
                """);
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("incremental", second.mainCompilationMode());
        assertTrue(Files.exists(widgetMeta));
        assertTrue(Files.exists(generatedSource("Gadget")));
        assertArrayEqualsBytes(before, Files.readAllBytes(widgetMeta));
    }

    @Test
    void removingAnAnnotationDeletesItsStaleGeneratedOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingProcessorJar(projectDir.resolve("processor-work")));

        assertEquals("full", buildService.build(projectDir, config(), cache()).mainCompilationMode());
        assertTrue(Files.exists(generatedSource("Widget")));

        source("src/main/java/com/example/Widget.java", """
                package com.example;

                public final class Widget {
                    public static String name() {
                        return "widget";
                    }
                }
                """);
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("incremental", second.mainCompilationMode());
        assertFalse(Files.exists(generatedSource("Widget")), "stale generated source must be deleted");
        assertFalse(Files.exists(generatedClass("Widget")), "stale generated class must be deleted");
        assertTrue(Files.exists(generatedSource("Gadget")), "untouched generated output must remain");
    }

    @Test
    void unattributedProcessorOutputForcesFullRecompile() throws Exception {
        setUpProject(AnnotationProcessorFixture.nonOriginatingProcessorJar(projectDir.resolve("processor-work")));

        BuildResult first = buildService.build(projectDir, config(), cache());
        assertEquals("full", first.mainCompilationMode());
        assertFalse(readState().processorAttributionComplete());

        source("src/main/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("full", second.mainCompilationMode());
        assertEquals("processor-unattributed-output", second.mainIncrementalFallbackReason());
    }

    @Test
    void aggregatingProcessorStaysOnFullRecompile() throws Exception {
        setUpProject(AnnotationProcessorFixture.aggregatingProcessorJar(projectDir.resolve("processor-work")));

        assertEquals("full", buildService.build(projectDir, config(), cache()).mainCompilationMode());

        source("src/main/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("full", second.mainCompilationMode());
        assertEquals("processor-aggregating", second.mainIncrementalFallbackReason());
    }

    @Test
    void editingAnnotatedSourceRegeneratesItsResourceOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingResourceProcessorJar(projectDir.resolve("processor-work")));

        BuildResult first = buildService.build(projectDir, config(), cache());
        assertEquals("full", first.mainCompilationMode());
        assertTrue(Files.exists(generatedResource("Widget")));
        assertTrue(Files.exists(generatedResource("Gadget")));
        assertTrue(readState().processorAttributionComplete());

        source("src/main/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("incremental", second.mainCompilationMode());
        assertEquals("", second.mainIncrementalFallbackReason());
        assertTrue(Files.exists(generatedResource("Widget")), "regenerated resource must exist");
        assertTrue(Files.exists(generatedResource("Gadget")), "untouched resource must remain");
    }

    @Test
    void removingAnAnnotationDeletesItsStaleResourceOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingResourceProcessorJar(projectDir.resolve("processor-work")));

        assertEquals("full", buildService.build(projectDir, config(), cache()).mainCompilationMode());
        assertTrue(Files.exists(generatedResource("Widget")));

        source("src/main/java/com/example/Widget.java", """
                package com.example;

                public final class Widget {
                    public static String name() {
                        return "widget";
                    }
                }
                """);
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("incremental", second.mainCompilationMode());
        assertFalse(Files.exists(generatedResource("Widget")), "stale generated resource must be deleted");
        assertTrue(Files.exists(generatedResource("Gadget")), "untouched generated resource must remain");
    }

    @Test
    void unattributedResourceOutputForcesFullRecompile() throws Exception {
        setUpProject(AnnotationProcessorFixture.nonOriginatingResourceProcessorJar(projectDir.resolve("processor-work")));

        BuildResult first = buildService.build(projectDir, config(), cache());
        assertEquals("full", first.mainCompilationMode());
        assertFalse(readState().processorAttributionComplete());

        source("src/main/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        BuildResult second = buildService.build(projectDir, config(), cache());

        assertEquals("full", second.mainCompilationMode());
        assertEquals("processor-unattributed-output", second.mainIncrementalFallbackReason());
    }

    private void setUpProject(Path processorJar) throws IOException {
        Path cached = cache().resolve("com/example/processor/1.0.0/processor-1.0.0.jar");
        Files.createDirectories(cached.getParent());
        Files.copy(processorJar, cached, StandardCopyOption.REPLACE_EXISTING);
        writeLockfile("""
                version = 1

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
        source("src/main/java/com/example/Tracked.java", """
                package com.example;

                public @interface Tracked {
                }
                """);
        source("src/main/java/com/example/Widget.java", trackedClass("Widget", "\"widget\""));
        source("src/main/java/com/example/Gadget.java", trackedClass("Gadget", "\"gadget\""));
    }

    private static String trackedClass(String name, String value) {
        return """
                package com.example;

                @Tracked
                public final class %s {
                    public static String name() {
                        return %s;
                    }
                }
                """.formatted(name, value);
    }

    private Path cache() {
        return projectDir.resolve("cache");
    }

    private Path generatedSource(String type) {
        return projectDir.resolve("target/generated/sources/annotations/com/example/" + type + "Meta.java");
    }

    private Path generatedClass(String type) {
        return projectDir.resolve("target/classes/com/example/" + type + "Meta.class");
    }

    private Path generatedResource(String type) {
        return projectDir.resolve("target/classes/meta/" + type + ".properties");
    }

    private IncrementalCompileState readState() {
        return new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/classes/.zolt-incremental-main.state"))
                .orElseThrow();
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, "generated output should be untouched");
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Widget")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private Path source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private void writeLockfile(String content) throws IOException {
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), projectDir.resolve("cache"), content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
