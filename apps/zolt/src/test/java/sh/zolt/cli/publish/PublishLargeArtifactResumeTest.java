package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestPackageEvidence;
import sh.zolt.cli.CliTestSupport.CommandResult;

final class PublishLargeArtifactResumeTest {
    private static final String JAR_PATH =
            "/maven2/com/example/large-publish/0.1.0/large-publish-0.1.0.jar";

    @Test
    void matchingLargeArtifactResumesAndDifferentContentConflicts(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("large-publish");
        Files.createDirectories(project.resolve("target"));
        Path jar = project.resolve("target/large-publish-0.1.0.jar");
        byte[] bytes = new byte[9 * 1024 * 1024];
        Arrays.fill(bytes, (byte) 17);
        Files.write(jar, bytes);
        Files.writeString(project.resolve("zolt.lock"), "version = 5\n");

        try (ImmutableRepository repository = ImmutableRepository.start()) {
            Files.writeString(project.resolve("zolt.toml"), memberConfig("large-publish") + """

                    [publish]
                    releaseRepository = "releases"

                    [publish.repositories.releases]
                    url = "%s"
                    """.formatted(repository.baseUri()));
            CliTestPackageEvidence.write(project);

            CommandResult first = execute("publish", "--cwd", project.toString());
            CommandResult matchingResume = execute("publish", "--cwd", project.toString());

            assertEquals(0, first.exitCode(), first.stdout() + first.stderr());
            assertEquals(0, matchingResume.exitCode(), matchingResume.stdout() + matchingResume.stderr());
            assertEquals(1, repository.putCount(JAR_PATH), "matching large artifact must not be re-PUT");

            repository.changeOneByte(JAR_PATH);
            CommandResult conflict = execute("publish", "--cwd", project.toString());

            assertEquals(1, conflict.exitCode(), conflict.stdout() + conflict.stderr());
            String output = conflict.stdout() + conflict.stderr();
            assertTrue(output.contains("already holds different content"), output);
            assertEquals(1, repository.putCount(JAR_PATH), "conflicting large artifact must not be overwritten");
        }
    }

    private static final class ImmutableRepository implements AutoCloseable {
        private final HttpServer server;
        private final URI baseUri;
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();
        private final Map<String, Integer> putCounts = new ConcurrentHashMap<>();

        private ImmutableRepository(HttpServer server) {
            this.server = server;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/");
        }

        static ImmutableRepository start() throws IOException {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
                throw exception;
            }
            ImmutableRepository repository = new ImmutableRepository(server);
            server.createContext("/", repository::handle);
            server.start();
            return repository;
        }

        URI baseUri() {
            return baseUri;
        }

        int putCount(String path) {
            return putCounts.getOrDefault(path, 0);
        }

        void changeOneByte(String path) {
            store.compute(path, (ignored, bytes) -> {
                byte[] changed = bytes.clone();
                changed[0] ^= 1;
                return changed;
            });
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                putCounts.merge(path, 1, Integer::sum);
                if (store.putIfAbsent(path, exchange.getRequestBody().readAllBytes()) != null) {
                    respond(exchange, 409, new byte[0]);
                    return;
                }
                respond(exchange, 201, new byte[0]);
                return;
            }
            byte[] body = store.get(path);
            respond(exchange, body == null ? 404 : 200,
                    body == null ? "missing".getBytes(StandardCharsets.UTF_8) : body);
        }

        private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            try (exchange) {
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
