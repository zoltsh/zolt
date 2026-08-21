package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.cli.CliTestPackageEvidence;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishCommandUploadTest {
    @TempDir
    private Path tempDir;

    @Test
    void publishUploadsArtifactAndGeneratedPom() throws IOException {
        Path projectDir = tempDir.resolve("publish-upload-release");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/publish-upload-release-0.1.0.jar");
        Path sourcesArtifact = projectDir.resolve("target/publish-upload-release-0.1.0-sources.jar");
        Path javadocArtifact = projectDir.resolve("target/publish-upload-release-0.1.0-javadoc.jar");
        Path testsArtifact = projectDir.resolve("target/publish-upload-release-0.1.0-tests.jar");
        Files.writeString(artifact, "fake package\n");
        Files.writeString(sourcesArtifact, "fake sources\n");
        Files.writeString(javadocArtifact, "fake javadoc\n");
        Files.writeString(testsArtifact, "fake tests\n");
        Files.writeString(projectDir.resolve("target/publish-upload-release-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/publish-upload-release-0.1.0.jar",
                  "archiveSha256": "%s",
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "thin",
                      "path": "target/publish-upload-release-0.1.0.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "sources",
                      "type": "jar",
                      "path": "target/publish-upload-release-0.1.0-sources.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "javadoc",
                      "type": "jar",
                      "path": "target/publish-upload-release-0.1.0-javadoc.jar",
                      "entries": 1,
                      "sha256": "%s"
                    },
                    {
                      "classifier": "tests",
                      "type": "jar",
                      "path": "target/publish-upload-release-0.1.0-tests.jar",
                      "entries": 1,
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(
                sha256(artifact),
                sha256(artifact),
                sha256(sourcesArtifact),
                sha256(javadocArtifact),
                sha256(testsArtifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        try (UploadRepository repository = UploadRepository.start()) {
            Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("publish-upload-release") + """

                    [package]
                    sources = true
                    javadoc = true
                    testJar = true

                    [publish]
                    release = "company-releases"

                    [publish.repositories.company-releases]
                    url = "%s"
                    """.formatted(repository.baseUri()));
            CliTestPackageEvidence.write(projectDir);

            CommandResult result = execute(
                    "publish",
                    "--cwd", projectDir.toString());

            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("Zolt publish"));
            assertTrue(result.stdout().contains("Coordinate: com.example:publish-upload-release:0.1.0"));
            assertTrue(result.stdout().contains("Target repository: company-releases"));
            assertTrue(result.stdout().contains("Artifact uploaded: com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0.jar"));
            assertTrue(result.stdout().contains("Supplemental artifact uploaded: com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0-sources.jar"));
            assertTrue(result.stdout().contains("Supplemental artifact uploaded: com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0-javadoc.jar"));
            assertTrue(result.stdout().contains("Supplemental artifact uploaded: com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0-tests.jar"));
            assertTrue(result.stdout().contains("POM uploaded: com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0.pom"));
            assertTrue(result.stdout().contains("Status: uploaded"));
            assertEquals("", result.stderr());
            assertEquals(
                    "fake package\n",
                    new String(repository.uploaded("/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0.jar"), StandardCharsets.UTF_8));
            assertEquals(
                    "fake sources\n",
                    new String(repository.uploaded("/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0-sources.jar"), StandardCharsets.UTF_8));
            assertEquals(
                    "fake javadoc\n",
                    new String(repository.uploaded("/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0-javadoc.jar"), StandardCharsets.UTF_8));
            assertEquals(
                    "fake tests\n",
                    new String(repository.uploaded("/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0-tests.jar"), StandardCharsets.UTF_8));
            assertTrue(new String(
                    repository.uploaded("/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0.pom"),
                    StandardCharsets.UTF_8).contains("<artifactId>publish-upload-release</artifactId>"));

            // Every uploaded file is accompanied by .md5, .sha1 and .sha256 sidecars.
            String artifactBase = "/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0.jar";
            assertEquals(
                    bareHex("fake package\n", "MD5"),
                    new String(repository.uploaded(artifactBase + ".md5"), StandardCharsets.UTF_8));
            assertEquals(
                    bareHex("fake package\n", "SHA-1"),
                    new String(repository.uploaded(artifactBase + ".sha1"), StandardCharsets.UTF_8));
            assertEquals(
                    bareHex("fake package\n", "SHA-256"),
                    new String(repository.uploaded(artifactBase + ".sha256"), StandardCharsets.UTF_8));
            for (String suffix : new String[] {"-sources.jar", "-javadoc.jar", "-tests.jar", ".pom"}) {
                String base = "/maven2/com/example/publish-upload-release/0.1.0/publish-upload-release-0.1.0" + suffix;
                assertEquals(32, new String(repository.uploaded(base + ".md5"), StandardCharsets.UTF_8).length());
                assertEquals(40, new String(repository.uploaded(base + ".sha1"), StandardCharsets.UTF_8).length());
                assertEquals(64, new String(repository.uploaded(base + ".sha256"), StandardCharsets.UTF_8).length());
            }
        }
    }

    private static String bareHex(String content, String algorithm) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm).digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable.", exception);
        }
    }

    private static final class UploadRepository implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> uploads = new HashMap<>();
        private final URI baseUri;

        private UploadRepository(HttpServer server) {
            this.server = server;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/");
        }

        static UploadRepository start() throws IOException {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
                return null;
            }
            UploadRepository repository = new UploadRepository(server);
            server.createContext("/", repository::handle);
            server.start();
            return repository;
        }

        URI baseUri() {
            return baseUri;
        }

        byte[] uploaded(String path) {
            byte[] bytes = uploads.get(path);
            if (bytes == null) {
                throw new AssertionError("No upload recorded for " + path);
            }
            return bytes.clone();
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("PUT".equals(exchange.getRequestMethod())) {
                uploads.put(exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes());
                respond(exchange, 201, "created".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 404, "missing".getBytes(StandardCharsets.UTF_8));
        }

        private static void respond(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
            try (exchange) {
                exchange.sendResponseHeaders(statusCode, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
