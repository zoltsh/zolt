package sh.zolt.build.testruntime.compile;

import static sh.zolt.build.testruntime.compile.TestCompileServiceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.AnnotationProcessorFixture;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCompileServiceTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void compilesMainSourcesBeforeTestSources() throws IOException {
        TestCompileServiceTestSupport.writeLockfile(projectDir, "version = 7\n");
        TestCompileServiceTestSupport.source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        TestCompileServiceTestSupport.source(projectDir, "src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return Main.message();
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(
                projectDir,
                config(),
                projectDir.resolve("cache"));

        assertEquals(1, result.buildResult().sourceCount());
        assertEquals(1, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
    }

    @Test
    void copiesMainAndTestResourcesDuringTestCompilation() throws IOException {
        TestCompileServiceTestSupport.writeLockfile(projectDir, "version = 7\n");
        TestCompileServiceTestSupport.source(projectDir, "src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        TestCompileServiceTestSupport.source(projectDir, "src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        TestCompileServiceTestSupport.source(projectDir, "src/main/resources/META-INF/native-image/reflect-config.json", "[]\n");
        TestCompileServiceTestSupport.source(projectDir, "src/test/resources/fixtures/input.txt", "fixture\n");

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals(1, result.buildResult().resourceCount());
        assertEquals(1, result.resourceCount());
        assertEquals("[]\n", Files.readString(projectDir.resolve("target/classes/META-INF/native-image/reflect-config.json")));
        assertEquals("fixture\n", Files.readString(projectDir.resolve("target/test-classes/fixtures/input.txt")));
    }

    @Test
    void testCompilationUsesTestProcessorClasspathAndGeneratedSources() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path processorJar = cacheRoot.resolve("com/example/test-processor/1.0.0/test-processor-1.0.0.jar");
        Files.createDirectories(processorJar.getParent());
        Files.copy(
                AnnotationProcessorFixture.processorJar(projectDir.resolve("processor-work")),
                processorJar,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        TestCompileServiceTestSupport.writeLockfile(projectDir, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:test-processor"
                version = "1.0.0"
                lane = "test-processor"
                resolvedScope = "test-processor"

                [[package]]
                id = "com.example:test-processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "test-processor"
                direct = true
                jar = "com/example/test-processor/1.0.0/test-processor-1.0.0.jar"
                dependencies = []
                """);
        TestCompileServiceTestSupport.source(projectDir, "src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), cacheRoot);

        assertEquals(0, result.buildResult().sourceCount());
        assertEquals(1, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve(
                "target/generated/test-sources/annotations/com/example/GeneratedMessage.java")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/GeneratedMessage.class")));
    }

    @Test
    void persistsGeneratedProducerIdentityInCanonicalTestFingerprint()
            throws IOException {
        TestCompileServiceTestSupport.writeLockfile(
                projectDir,
                "version = 7\n");
        TestCompileServiceTestSupport.source(
                projectDir,
                "src/test/java/com/example/MainTest.java",
                "package com.example; public final class MainTest {}\n");
        TestCompileServiceTestSupport.source(
                projectDir,
                "fixtures.sql",
                "seed\n");
        String toml = """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["fixtures.sql"]
                output = "target/generated/test-fixtures"
                required = false
                """.formatted(
                System.getProperty(
                        "java.specification.version"));
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                toml);
        ProjectConfig config =
                new ZoltTomlParser().parse(toml);

        testCompileService.compileTests(
                projectDir,
                config,
                projectDir.resolve("cache"));
        TestCompileResult second =
                testCompileService.compileTests(
                        projectDir,
                        config,
                        projectDir.resolve("cache"));
        String fingerprint = Files.readString(
                projectDir.resolve(
                        "target/test-classes/.zolt-build-test.fingerprint"));

        assertTrue(fingerprint.contains(
                "[generatedProducerFingerprints]\n"));
        assertTrue(fingerprint.contains(
                "test|fixtures|declared-root|"));
        assertTrue(second.testCompilationSkipped());
    }
}
