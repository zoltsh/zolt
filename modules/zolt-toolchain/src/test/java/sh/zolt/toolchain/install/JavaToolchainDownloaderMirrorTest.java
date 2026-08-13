package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import sh.zolt.net.NetworkTransport;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaToolchainDownloaderMirrorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadRoutesGithubUrlThroughConfiguredMirror(@TempDir Path directory) throws IOException {
        byte[] payload = "managed-jdk-archive".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> requestedPath = new AtomicReference<>();
        int port = startMirror(payload, requestedPath);

        ToolchainDownloadMirror mirror = ToolchainDownloadMirror.of("http://127.0.0.1:" + port);
        JavaToolchainDownloader downloader = new JavaToolchainDownloader(NetworkTransport.direct(), mirror);
        JavaToolchainArtifact artifact = new JavaToolchainArtifact(
                URI.create("https://github.com/adoptium/temurin21-binaries/releases/download/"
                        + "jdk-21.0.11%2B10/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz"),
                JavaToolchainArchiveFormat.TAR_GZ,
                Optional.of("102c477398d1ad3aac43e974ffd4c88706e5796b0a5c87df64fc7ffdd432f316"),
                true);
        Path destination = directory.resolve("jdk.tar.gz");

        downloader.download(artifact, destination);

        assertArrayEquals(payload, Files.readAllBytes(destination));
        assertEquals(
                "/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/"
                        + "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz",
                requestedPath.get());
    }

    private int startMirror(byte[] payload, AtomicReference<String> requestedPath) throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
        }
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            try (exchange) {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }
}
