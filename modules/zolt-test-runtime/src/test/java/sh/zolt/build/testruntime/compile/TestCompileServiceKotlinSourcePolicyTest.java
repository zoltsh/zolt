package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResult;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.cache.RemoteBuildCacheClient;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class TestCompileServiceKotlinSourcePolicyTest {
    @TempDir
    private Path projectDir;

    @Test
    void mixedGroovyAndKotlinTestsFailBeforeTestCacheReuseOrOwnedOutputCleanup() throws IOException {
        Path groovyTest = projectDir.resolve("src/test/groovy/com/example/MainSpec.groovy");
        Path kotlinTest = projectDir.resolve("src/test/kotlin/com/example/MainTest.kt");
        Path staleClass = projectDir.resolve("target/test-classes/stale/Existing.class");
        Path cacheMarker = projectDir.resolve("build-cache/do-not-touch.marker");
        write(groovyTest, new byte[] {1});
        write(kotlinTest, new byte[] {2});
        write(staleClass, new byte[] {2, 3, 4});
        write(cacheMarker, new byte[] {5, 6, 7});
        BuildResult mainBuild = new BuildResult(
                Optional.empty(),
                0,
                0,
                projectDir.resolve("target/classes"),
                "");

        try (CountingRemoteCache remote = new CountingRemoteCache()) {
            BuildCacheService cache = BuildCacheService.create(
                    new BuildCacheSettings(true, cacheMarker.getParent(), 0L),
                    Optional.of(new RemoteBuildCacheClient(
                            HttpClient.newHttpClient(), remote.baseUri(), Optional.empty(), false)),
                    "test-version");

            BuildException exception = assertThrows(
                    BuildException.class,
                    () -> new TestCompileService()
                            .withBuildCache(cache)
                            .compileTests(
                                    projectDir,
                                    mixedTestConfig(),
                                    emptyClasspaths(),
                                    mainBuild));

            assertEquals(
                    "The test source set combines Groovy and Kotlin, which Zolt does not support.",
                    exception.actionableError().summary());
            assertEquals(
                    "Use either Groovy or Kotlin for the test source set, then run `zolt test` again.",
                    exception.actionableError().remediation());
            assertEquals(0, remote.requestCount());
            assertArrayEquals(new byte[] {2, 3, 4}, Files.readAllBytes(staleClass));
            assertArrayEquals(new byte[] {5, 6, 7}, Files.readAllBytes(cacheMarker));
        }
    }

    @Test
    void modularMixedTestsFailBeforeTestCacheReuseOrOwnedOutputCleanup() throws IOException {
        Path moduleInfo = projectDir.resolve("src/test/java/module-info.java");
        Path kotlinTest = projectDir.resolve("src/test/kotlin/com/example/MainTest.kt");
        Path staleClass = projectDir.resolve("target/test-classes/stale/Existing.class");
        Path cacheMarker = projectDir.resolve("build-cache/do-not-touch.marker");
        write(moduleInfo, "module demo {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        write(kotlinTest, new byte[] {1});
        write(staleClass, new byte[] {2, 3, 4});
        write(cacheMarker, new byte[] {5, 6, 7});
        BuildResult mainBuild = new BuildResult(
                Optional.empty(),
                0,
                0,
                projectDir.resolve("target/classes"),
                "");

        try (CountingRemoteCache remote = new CountingRemoteCache()) {
            BuildCacheService cache = BuildCacheService.create(
                    new BuildCacheSettings(true, cacheMarker.getParent(), 0L),
                    Optional.of(new RemoteBuildCacheClient(
                            HttpClient.newHttpClient(), remote.baseUri(), Optional.empty(), false)),
                    "test-version");

            KotlinCompileException exception = assertThrows(
                    KotlinCompileException.class,
                    () -> new TestCompileService()
                            .withBuildCache(cache)
                            .compileTests(
                                    projectDir,
                                    mixedJavaKotlinTestConfig(),
                                    emptyClasspaths(),
                                    mainBuild));

            assertEquals(
                    "Kotlin test compilation is not supported when the test source set contains"
                            + " module-info.java. Remove module-info.java or keep this test source set"
                            + " Java-only until modular Kotlin/Java joint compilation is supported.",
                    exception.getMessage());
            assertEquals(0, remote.requestCount());
            assertArrayEquals(new byte[] {2, 3, 4}, Files.readAllBytes(staleClass));
            assertArrayEquals(new byte[] {5, 6, 7}, Files.readAllBytes(cacheMarker));
        }
    }

    @Test
    void unsupportedKotlinTestOptionFailsBeforeCacheReuseOrOwnedOutputCleanup() throws IOException {
        Path staleClass = projectDir.resolve("target/test-classes/stale/Existing.class");
        Path fingerprint = projectDir.resolve("target/test-classes/.zolt-build-test.fingerprint");
        Path cacheMarker = projectDir.resolve("build-cache/do-not-touch.marker");
        write(staleClass, new byte[] {1, 2, 3});
        write(fingerprint, new byte[] {4, 5, 6});
        write(cacheMarker, new byte[] {7, 8, 9});
        BuildResult mainBuild = new BuildResult(
                Optional.empty(),
                0,
                0,
                projectDir.resolve("target/classes"),
                "");

        try (CountingRemoteCache remote = new CountingRemoteCache()) {
            BuildCacheService cache = BuildCacheService.create(
                    new BuildCacheSettings(true, cacheMarker.getParent(), 0L),
                    Optional.of(new RemoteBuildCacheClient(
                            HttpClient.newHttpClient(), remote.baseUri(), Optional.empty(), false)),
                    "test-version");

            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> new TestCompileService()
                            .withBuildCache(cache)
                            .compileTests(
                                    projectDir,
                                    unsupportedTestOptionConfig(),
                                    emptyClasspaths(),
                                    mainBuild));

            assertTrue(failure.getMessage().contains("[compiler.test].args"), failure.getMessage());
            assertTrue(failure.getMessage().contains("1.9.0"), failure.getMessage());
            assertEquals(0, remote.requestCount());
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(staleClass));
            assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(fingerprint));
            assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(cacheMarker));
        }
    }

    private static ProjectConfig mixedTestConfig() {
        return testConfig(List.of("src/test/groovy"), List.of("src/test/kotlin"));
    }

    private static ProjectConfig mixedJavaKotlinTestConfig() {
        return testConfig(List.of(), List.of("src/test/kotlin"));
    }

    private static ProjectConfig unsupportedTestOptionConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "old-kotlin-test"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.kotlin]
                version = "1.9.0"

                [compiler.test]
                args = ["-Xannotation-default-target=param-property"]
                """);
    }

    private static ProjectConfig testConfig(
            List<String> groovyTestRoots,
            List<String> kotlinTestRoots) {
        ProjectConfig config = TestCompileServiceTestSupport.config();
        BuildSettings defaults = config.build();
        return config.withBuildSettings(new BuildSettings(
                defaults.source(),
                defaults.sourceRoots(),
                defaults.test(),
                defaults.outputRoot(),
                defaults.output(),
                defaults.testOutput(),
                defaults.testSources(),
                groovyTestRoots,
                kotlinTestRoots,
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.metadata()));
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }

    private static void write(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    private static final class CountingRemoteCache implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();

        CountingRemoteCache() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requestCount.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        int requestCount() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
