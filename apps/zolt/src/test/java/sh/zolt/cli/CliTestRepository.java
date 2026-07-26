package sh.zolt.cli;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CliTestRepository implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, byte[]> uploads = new HashMap<>();
    private final Map<String, String> authorizations = new ConcurrentHashMap<>();
    private final URI baseUri;
    private volatile String requiredAuthorization;

    private CliTestRepository(HttpServer server) {
        this.server = server;
        this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/");
    }

    public static CliTestRepository start() throws IOException {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
            return null;
        }
        CliTestRepository repository = new CliTestRepository(server);
        server.createContext("/", repository::handle);
        server.start();
        return repository;
    }

    public URI baseUri() {
        return baseUri;
    }

    public void requireBearerToken(String token) {
        requiredAuthorization = "Bearer " + token;
    }

    public void clearAuthorizations() {
        authorizations.clear();
    }

    public Map<String, String> authorizations() {
        return Map.copyOf(authorizations);
    }

    public void addArtifact(String groupId, String artifactId, String version, String pom) {
        String base = "/maven2/"
                + groupId.replace('.', '/')
                + "/"
                + artifactId
                + "/"
                + version
                + "/"
                + artifactId
                + "-"
                + version;
        responses.put(base + ".pom", pom.getBytes(StandardCharsets.UTF_8));
        responses.put(base + ".jar", emptyZip());
    }

    public void addClassifiedArtifact(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String extension) {
        String base = "/maven2/"
                + groupId.replace('.', '/')
                + "/"
                + artifactId
                + "/"
                + version
                + "/"
                + artifactId
                + "-"
                + version;
        responses.put(
                base + "-" + classifier + "." + extension,
                emptyZip());
    }

    public void addTypedArtifact(
            String groupId,
            String artifactId,
            String version,
            String extension) {
        String base = "/maven2/"
                + groupId.replace('.', '/')
                + "/"
                + artifactId
                + "/"
                + version
                + "/"
                + artifactId
                + "-"
                + version;
        responses.put(base + "." + extension, emptyZip());
    }

    private static byte[] emptyZip() {
        return new byte[] {
                0x50, 0x4b, 0x05, 0x06,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0
        };
    }

    public byte[] uploaded(String path) {
        byte[] bytes = uploads.get(path);
        if (bytes == null) {
            throw new AssertionError("No upload recorded for " + path);
        }
        return bytes.clone();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.put(
                exchange.getRequestURI().getPath(),
                authorization == null ? "<none>" : authorization);
        if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
            respond(exchange, 401, "authentication required".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("PUT".equals(exchange.getRequestMethod())) {
            uploads.put(exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes());
            respond(exchange, 201, "created".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = responses.get(exchange.getRequestURI().getPath());
        if (body == null) {
            respond(exchange, 404, "missing".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, body);
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
