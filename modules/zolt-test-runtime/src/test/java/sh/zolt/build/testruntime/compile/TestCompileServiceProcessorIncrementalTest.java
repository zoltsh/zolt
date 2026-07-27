package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.AnnotationProcessorFixture;
import sh.zolt.build.WorkerAttributionTestSupport;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the isolating annotation-processor incremental fast path for TEST-scope
 * compilation, driven through the real javac worker (compiled from source and enabled for the class) so
 * generated-output attribution is actually captured and the test-scope fast path can engage.
 */
final class TestCompileServiceProcessorIncrementalTest {
    private static WorkerAttributionTestSupport worker;

    private final TestCompileService testCompileService = new TestCompileService();

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
    void editingAnnotatedTestSourceRecompilesIncrementallyAndRegeneratesItsOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingProcessorJar(projectDir.resolve("processor-work")));

        TestCompileResult first = compile();
        assertEquals("full", first.testCompilationMode());
        assertTrue(Files.exists(generatedTestSource("Widget")));
        assertTrue(Files.exists(generatedTestSource("Gadget")));
        assertTrue(readTestState().processorAttributionComplete());

        source("src/test/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        TestCompileResult second = compile();

        assertEquals("incremental", second.testCompilationMode());
        assertEquals("", second.testIncrementalFallbackReason());
        assertTrue(Files.exists(generatedTestSource("Widget")));
        assertTrue(Files.exists(generatedTestClass("Widget")));
    }

    @Test
    void removingAnAnnotationDeletesItsStaleTestOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingProcessorJar(projectDir.resolve("processor-work")));

        assertEquals("full", compile().testCompilationMode());
        assertTrue(Files.exists(generatedTestSource("Widget")));

        source("src/test/java/com/example/Widget.java", """
                package com.example;

                public final class Widget {
                    public static String name() {
                        return "widget";
                    }
                }
                """);
        TestCompileResult second = compile();

        assertEquals("incremental", second.testCompilationMode());
        assertFalse(Files.exists(generatedTestSource("Widget")), "stale generated source must be deleted");
        assertFalse(Files.exists(generatedTestClass("Widget")), "stale generated class must be deleted");
        assertTrue(Files.exists(generatedTestSource("Gadget")), "untouched generated output must remain");
    }

    @Test
    void editingAnnotatedTestSourceRegeneratesItsResourceOutput() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingResourceProcessorJar(projectDir.resolve("processor-work")));

        TestCompileResult first = compile();
        assertEquals("full", first.testCompilationMode());
        assertTrue(Files.exists(generatedTestResource("Widget")));
        assertTrue(Files.exists(generatedTestResource("Gadget")));
        assertTrue(readTestState().processorAttributionComplete());

        source("src/test/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        TestCompileResult second = compile();

        assertEquals("incremental", second.testCompilationMode());
        assertEquals("", second.testIncrementalFallbackReason());
        assertTrue(Files.exists(generatedTestResource("Widget")), "regenerated resource must exist");
        assertTrue(Files.exists(generatedTestResource("Gadget")), "untouched resource must remain");
    }

    @Test
    void unattributedTestProcessorOutputForcesFullRecompile() throws Exception {
        setUpProject(AnnotationProcessorFixture.nonOriginatingProcessorJar(projectDir.resolve("processor-work")));

        TestCompileResult first = compile();
        assertEquals("full", first.testCompilationMode());
        assertFalse(readTestState().processorAttributionComplete());

        source("src/test/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        TestCompileResult second = compile();

        assertEquals("full", second.testCompilationMode());
        assertEquals("processor-unattributed-output", second.testIncrementalFallbackReason());
    }

    @Test
    void staleSchemaTestStateForcesOneFullRecompileThenRecovers() throws Exception {
        setUpProject(AnnotationProcessorFixture.attributingProcessorJar(projectDir.resolve("processor-work")));

        assertEquals("full", compile().testCompilationMode());
        assertTrue(readTestState().processorAttributionComplete());

        Path statePath = projectDir.resolve("target/test-classes/.zolt-incremental-test.state");
        String currentState = Files.readString(statePath);
        String staleState = currentState.replaceFirst("(?m)^version=.*$", "version=0");
        assertFalse(staleState.equals(currentState), "state version must be replaced");
        Files.writeString(statePath, staleState);

        source("src/test/java/com/example/Widget.java", trackedClass("Widget", "\"changed\""));
        TestCompileResult migrated = compile();
        assertEquals("full", migrated.testCompilationMode());
        assertEquals("missing-state", migrated.testIncrementalFallbackReason());

        source("src/test/java/com/example/Widget.java", trackedClass("Widget", "\"again\""));
        assertEquals("incremental", compile().testCompilationMode());
    }

    private TestCompileResult compile() {
        return testCompileService.compileTests(projectDir, TestCompileServiceTestSupport.config(), cache());
    }

    private void setUpProject(Path processorJar) throws IOException {
        Path cached = cache().resolve("com/example/test-processor/1.0.0/test-processor-1.0.0.jar");
        Files.createDirectories(cached.getParent());
        Files.copy(processorJar, cached, StandardCopyOption.REPLACE_EXISTING);
        TestCompileServiceTestSupport.writeLockfile(projectDir, """
                version = 1

                [[package]]
                id = "com.example:test-processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "test-processor"
                direct = true
                jar = "com/example/test-processor/1.0.0/test-processor-1.0.0.jar"
                dependencies = []
                """);
        source("src/test/java/com/example/Tracked.java", """
                package com.example;

                public @interface Tracked {
                }
                """);
        source("src/test/java/com/example/Widget.java", trackedClass("Widget", "\"widget\""));
        source("src/test/java/com/example/Gadget.java", trackedClass("Gadget", "\"gadget\""));
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

    private Path source(String path, String content) throws IOException {
        return TestCompileServiceTestSupport.source(projectDir, path, content);
    }

    private Path generatedTestSource(String type) {
        return projectDir.resolve("target/generated/test-sources/annotations/com/example/" + type + "Meta.java");
    }

    private Path generatedTestClass(String type) {
        return projectDir.resolve("target/test-classes/com/example/" + type + "Meta.class");
    }

    private Path generatedTestResource(String type) {
        return projectDir.resolve("target/test-classes/meta/" + type + ".properties");
    }

    private IncrementalCompileState readTestState() {
        return new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/test-classes/.zolt-incremental-test.state"))
                .orElseThrow();
    }
}
