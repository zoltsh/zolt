package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.compile.EffectiveCompilerIdentity;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCompileServiceGroovyCompilerJarTest extends TestCompileServiceGroovyTestSupport {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheHome;

    @Test
    void compilesGroovyTestSourcesWithProjectProvidedCompilerJar() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeGroovyCompilerLock(cacheRoot, "4.0.24");
        TestCompileServiceGroovyTest.source(projectDir, "src/test/groovy/com/example/MainSpec.groovy", """
                package com.example

                final class MainSpec {
                }
                """);
        ProjectConfig config = config().withBuildSettings(new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of("src/test/groovy")));

        TestCompileResult first = testCompileService.compileTests(projectDir, config, cacheRoot);
        TestCompileResult second = testCompileService.compileTests(projectDir, config, cacheRoot);

        assertEquals(1, first.sourceCount());
        assertEquals("full", first.testCompilationMode());
        assertEquals("groovy-test-sources", first.testIncrementalFallbackReason());
        assertTrue(first.compilerOutput().contains("fake groovy compiler"));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainSpec.class")));
        IncrementalCompileState state = new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/test-classes/.zolt-incremental-test.state"))
                .orElseThrow();
        assertTrue(state.fallbackReasons().contains("groovy-test-sources"));
        assertTrue(state.fallbackReasons().contains("unreadable-class-output"));
        assertNotEquals(
                EffectiveCompilerIdentity.of(new JdkDetector().detect(config.project().java())),
                state.compilerIdentity());
        assertTrue(second.testCompilationSkipped());
    }

    @Test
    void compilerVersionChangeCannotReuseTheOldTestFingerprintOrCacheEntry() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        TestCompileService service = new TestCompileService()
                .withBuildCache(BuildCacheService.create(
                        new BuildCacheSettings(true, cacheHome.resolve("build-cache"), 0L),
                        "test-version"));
        writeGroovyCompilerLock(cacheRoot, "4.0.24");
        TestCompileServiceGroovyTest.source(projectDir, "src/test/groovy/com/example/MainSpec.groovy", """
                package com.example

                final class MainSpec {
                }
                """);
        ProjectConfig config = config().withBuildSettings(new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of("src/test/groovy")));

        TestCompileResult first = service.compileTests(projectDir, config, cacheRoot);
        String firstIdentity = state().compilerIdentity();
        wipeTestOutput();
        writeGroovyCompilerLock(cacheRoot, "4.0.25");

        TestCompileResult changed = service.compileTests(projectDir, config, cacheRoot);
        String changedIdentity = state().compilerIdentity();

        assertFalse(first.testCompilationSkipped());
        assertFalse(changed.testCompilationSkipped());
        assertEquals("full", changed.testCompilationMode());
        assertNotEquals(firstIdentity, changedIdentity);

        wipeTestOutput();
        TestCompileResult restored = service.compileTests(projectDir, config, cacheRoot);

        assertEquals("restored", restored.testCompilationMode());
    }

    private void writeGroovyCompilerLock(Path cacheRoot, String version) throws IOException {
        Path groovyJar = cacheRoot.resolve(
                "org/apache/groovy/groovy/" + version + "/groovy-" + version + ".jar");
        createFakeGroovyCompilerJar(projectDir, groovyJar, version);
        TestCompileServiceGroovyTest.writeLockfile(projectDir, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.apache.groovy:groovy"
                version = "%s"
                lane = "test"
                resolvedScope = "test"

                [[package]]
                id = "org.apache.groovy:groovy"
                version = "%s"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/apache/groovy/groovy/%s/groovy-%s.jar"
                dependencies = []
                """.formatted(version, version, version, version));
    }

    private IncrementalCompileState state() {
        return new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/test-classes/.zolt-incremental-test.state"))
                .orElseThrow();
    }

    private void wipeTestOutput() throws IOException {
        Path output = projectDir.resolve("target/test-classes");
        try (var paths = Files.walk(output)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static ProjectConfig config() {
        return TestCompileServiceGroovyTest.config();
    }
}
