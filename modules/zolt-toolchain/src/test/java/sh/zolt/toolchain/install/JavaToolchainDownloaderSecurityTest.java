package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaToolchainDownloaderSecurityTest {
    @TempDir
    private Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void explicitFileMirrorPermitsChecksummedLocalDevelopmentArchive() throws Exception {
        byte[] body = "local-managed-jdk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path mirrorRoot = Files.createDirectory(tempDir.resolve("file-mirror"));
        Files.write(mirrorRoot.resolve("jdk.tar.gz"), body);
        JavaToolchainArtifact artifact = artifact("https://github.com/jdk.tar.gz", sha256(body));
        JavaToolchainDownloader downloader = new JavaToolchainDownloader(
                NetworkTransport.direct(),
                ToolchainDownloadMirror.of(mirrorRoot.toUri().toString()));
        Path destination = tempDir.resolve("downloaded.tar.gz");

        downloader.download(artifact, destination);

        assertArrayEquals(body, Files.readAllBytes(destination));
    }

    @Test
    void declaredOversizeIsRejectedWithoutPublishingPartialArchive() throws Exception {
        byte[] body = new byte[] {1, 2, 3, 4, 5};
        int port = startServer(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        URI mirror = URI.create("http://127.0.0.1:" + port);
        JavaToolchainDownloader downloader = new JavaToolchainDownloader(
                HttpClient.newHttpClient(),
                ignored -> mirror,
                4);
        Path destination = tempDir.resolve("declared.tar.gz");
        Files.writeString(destination, "trusted-existing-archive");

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> downloader.download(artifact("https://github.com/jdk.tar.gz", sha256(body)), destination));

        assertTrue(exception.getMessage().contains("declared 5 bytes exceeds"));
        assertTrue(Files.readString(destination).equals("trusted-existing-archive"));
        assertNoPartialDownloads();
    }

    @Test
    void chunkedOversizeIsRejectedWithoutPublishingPartialArchive() throws Exception {
        byte[] body = new byte[] {1, 2, 3, 4, 5};
        int port = startServer(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body, 0, 3);
                exchange.getResponseBody().flush();
                exchange.getResponseBody().write(body, 3, 2);
            }
        });
        URI mirror = URI.create("http://127.0.0.1:" + port);
        JavaToolchainDownloader downloader = new JavaToolchainDownloader(
                HttpClient.newHttpClient(),
                ignored -> mirror,
                4);
        Path destination = tempDir.resolve("chunked.tar.gz");

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> downloader.download(artifact("https://github.com/jdk.tar.gz", sha256(body)), destination));

        assertTrue(exception.getMessage().contains("received more than the 4 byte limit"));
        assertFalse(Files.exists(destination));
        assertNoPartialDownloads();
    }

    @Test
    void artifactTypeRejectsMissingChecksumAndNonHttpsCanonicalUris() {
        IllegalArgumentException missingChecksum = assertThrows(
                IllegalArgumentException.class,
                () -> new JavaToolchainArtifact(
                        URI.create("https://example.test/jdk.tar.gz"),
                        JavaToolchainArchiveFormat.TAR_GZ,
                        Optional.empty(),
                        true));
        assertTrue(missingChecksum.getMessage().contains("SHA-256"));

        for (String uri : java.util.List.of(
                "http://example.test/jdk.tar.gz",
                "file:///tmp/jdk.tar.gz")) {
            IllegalArgumentException insecure = assertThrows(
                    IllegalArgumentException.class,
                    () -> artifact(uri, "a".repeat(64)));
            assertTrue(insecure.getMessage().contains("explicit mirror policy"));
        }
    }

    private int startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
        }
        server.createContext("/", handler);
        server.start();
        return server.getAddress().getPort();
    }

    private void assertNoPartialDownloads() throws IOException {
        try (var paths = Files.list(tempDir)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".download")));
        }
    }

    private static JavaToolchainArtifact artifact(String uri, String sha256) {
        return new JavaToolchainArtifact(
                URI.create(uri),
                JavaToolchainArchiveFormat.TAR_GZ,
                Optional.of(sha256),
                true);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
