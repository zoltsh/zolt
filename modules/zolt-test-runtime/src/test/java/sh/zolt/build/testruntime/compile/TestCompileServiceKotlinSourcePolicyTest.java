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
import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResult;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.cache.RemoteBuildCacheClient;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;

final class TestCompileServiceKotlinSourcePolicyTest {
    @TempDir
    private Path projectDir;

    @Test
    void failsBeforeTestCacheReuseOrOwnedOutputCleanup() throws IOException {
        Path kotlin = projectDir.resolve("src/test/java/com/example/MainTest.kt");
        Path staleClass = projectDir.resolve("target/test-classes/stale/Existing.class");
        Path cacheMarker = projectDir.resolve("build-cache/do-not-touch.marker");
        write(kotlin, new byte[] {1});
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
                                    TestCompileServiceTestSupport.config(),
                                    emptyClasspaths(),
                                    mainBuild));

            assertEquals(
                    "Zolt recognized Kotlin test sources, but Kotlin compilation is not available yet.",
                    exception.actionableError().summary());
            assertEquals(0, remote.requestCount());
            assertArrayEquals(new byte[] {2, 3, 4}, Files.readAllBytes(staleClass));
            assertArrayEquals(new byte[] {5, 6, 7}, Files.readAllBytes(cacheMarker));
        }
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
