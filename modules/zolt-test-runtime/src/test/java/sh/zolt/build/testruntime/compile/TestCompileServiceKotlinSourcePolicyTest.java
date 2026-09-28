package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import sh.zolt.build.BuildResult;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.cache.RemoteBuildCacheClient;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;

final class TestCompileServiceKotlinSourcePolicyTest {
    @TempDir
    private Path projectDir;

    @Test
    void failsBeforeTestCacheReuseOrOwnedOutputCleanup() throws IOException {
        Path kotlinMain = projectDir.resolve("src/main/java/com/example/Main.kt");
        Path kotlinTest = projectDir.resolve("src/test/kotlin/com/example/MainTest.kt");
        Path staleClass = projectDir.resolve("target/test-classes/stale/Existing.class");
        Path cacheMarker = projectDir.resolve("build-cache/do-not-touch.marker");
        write(kotlinMain, new byte[] {1});
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

            KotlinCompileException exception = assertThrows(
                    KotlinCompileException.class,
                    () -> new TestCompileService()
                            .withBuildCache(cache)
                            .compileTests(
                                    projectDir,
                                    kotlinTestConfig(),
                                    emptyClasspaths(),
                                    mainBuild));

            assertEquals(
                    "Kotlin test compilation is not supported when the main source set also contains Kotlin. "
                            + "Keep the main source set Java-only until Kotlin module metadata participates in test"
                            + " compilation fingerprints.",
                    exception.getMessage());
            assertEquals(0, remote.requestCount());
            assertArrayEquals(new byte[] {2, 3, 4}, Files.readAllBytes(staleClass));
            assertArrayEquals(new byte[] {5, 6, 7}, Files.readAllBytes(cacheMarker));
        }
    }

    private static ProjectConfig kotlinTestConfig() {
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
                defaults.groovyTestSources(),
                List.of("src/test/kotlin"),
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
