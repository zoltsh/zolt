package sh.zolt.build;

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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.cache.RemoteBuildCacheClient;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class BuildServiceKotlinSourcePolicyTest {
    @TempDir
    private Path projectDir;

    @Test
    void failsBeforeBuildCacheReuseOrOwnedOutputCleanup() throws IOException {
        Path kotlin = projectDir.resolve("src/main/java/com/example/Main.kt");
        Path staleClass = projectDir.resolve("target/classes/stale/Existing.class");
        Path cacheMarker = projectDir.resolve("build-cache/do-not-touch.marker");
        write(kotlin, "package com.example\nclass Main\n");
        write(staleClass, new byte[] {1, 2, 3});
        write(cacheMarker, new byte[] {4, 5, 6});
        try (CountingRemoteCache remote = new CountingRemoteCache()) {
            BuildCacheService cache = BuildCacheService.create(
                    new BuildCacheSettings(true, cacheMarker.getParent(), 0L),
                    Optional.of(new RemoteBuildCacheClient(
                            HttpClient.newHttpClient(), remote.baseUri(), Optional.empty(), false)),
                    "test-version");

            BuildException exception = assertThrows(
                    BuildException.class,
                    () -> new BuildService()
                            .withBuildCache(cache)
                            .build(projectDir, config(), emptyClasspaths()));

            assertEquals(
                    "Zolt recognized Kotlin main sources, but Kotlin compilation is not available yet.",
                    exception.actionableError().summary());
            assertEquals(0, remote.requestCount());
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(staleClass));
            assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(cacheMarker));
        }
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void write(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
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
