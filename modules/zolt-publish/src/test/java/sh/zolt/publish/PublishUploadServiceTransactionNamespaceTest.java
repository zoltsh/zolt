package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishUploadServiceTransactionNamespaceTest {
    @TempDir
    Path tempDir;

    @Test
    void interruptedReleaseDoesNotBlockPublishingTheNextVersion() throws Exception {
        Path project = tempDir.resolve("resumable-lib");
        try (Recorder recorder = Recorder.start()) {
            writeProject(project, "1.0.0", recorder.baseUri());
            PublishUploadService service = new PublishUploadService();
            String failedPath =
                    "/com/example/resumable-lib/1.0.0/resumable-lib-1.0.0.pom.sha256";
            recorder.failPutPathSuffix = failedPath;

            assertThrows(PublishException.class, () -> service.upload(project));
            Path stagingRoot = project.resolve("target/publish/publish-staging");
            Path oldTransaction = PublicationTransactionManifest.transactionPath(
                    stagingRoot,
                    recorder.baseUri().normalize().toString(),
                    "com.example:resumable-lib:1.0.0");
            assertTrue(Files.isRegularFile(oldTransaction), "the interrupted 1.0.0 state remains resumable");

            writeProject(project, "1.0.1", recorder.baseUri());
            recorder.failPutPathSuffix = null;
            service.upload(project);

            assertTrue(
                    recorder.received("/com/example/resumable-lib/1.0.1/resumable-lib-1.0.1.jar"),
                    "the independent 1.0.1 release uploads normally");
            assertTrue(Files.isRegularFile(oldTransaction), "publishing 1.0.1 does not abandon 1.0.0");
            Path newTransaction = PublicationTransactionManifest.transactionPath(
                    stagingRoot,
                    recorder.baseUri().normalize().toString(),
                    "com.example:resumable-lib:1.0.1");
            assertTrue(Files.notExists(newTransaction), "successful 1.0.1 state is removed");
        }
    }

    private static void writeProject(Path project, String version, URI repository) throws IOException {
        Path target = project.resolve("target");
        Files.createDirectories(target);
        Path artifact = target.resolve("resumable-lib-" + version + ".jar");
        Files.writeString(artifact, "artifact " + version + "\n");
        Files.writeString(
                artifact.resolveSibling(artifact.getFileName() + ".zolt-package.json"),
                """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/resumable-lib-%s.jar",
                  "archiveSha256": "sha256:%s"
                }
                """.formatted(version, sha256(artifact)));
        Files.writeString(project.resolve("zolt.lock"), "version = 3\n");
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "resumable-lib"
                version = "%s"
                group = "com.example"
                java = "%d"

                [publish]
                releaseRepository = "local"

                [publish.repositories.local]
                url = "%s"
                """.formatted(version, Runtime.version().feature(), repository));
        PublishTestPackageEvidence.write(project);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Recorder implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
        private final URI baseUri;
        private volatile String failPutPathSuffix;

        private Recorder(HttpServer server) {
            this.server = server;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        static Recorder start() throws IOException {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
                return null;
            }
            Recorder recorder = new Recorder(server);
            server.createContext("/", recorder::handle);
            server.start();
            return recorder;
        }

        URI baseUri() {
            return baseUri;
        }

        boolean received(String path) {
            return uploads.containsKey(path);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = uploads.get(path);
                respond(exchange, body == null ? 404 : 200, body == null ? bytes("missing") : body);
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (failPutPathSuffix != null && path.endsWith(failPutPathSuffix)) {
                    respond(exchange, 500, bytes("failed"));
                    return;
                }
                uploads.put(path, body);
                respond(exchange, 201, bytes("created"));
                return;
            }
            respond(exchange, 404, bytes("missing"));
        }

        private static byte[] bytes(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            try (exchange) {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
