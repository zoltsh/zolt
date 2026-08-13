package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.maven.CoordinateParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class MavenRepositoryClientTestSupport {
    protected final CoordinateParser parser = new CoordinateParser();
    protected final MavenRepositoryClient client = new MavenRepositoryClient();
    protected final Map<String, byte[]> responses = new HashMap<>();
    protected final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    protected HttpServer server;
    protected URI baseUri;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
            return;
        }
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", this::handle);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    protected void put(String path, String body) {
        responses.put("/maven2/" + path, body.getBytes(StandardCharsets.UTF_8));
    }

    protected void handle(HttpExchange exchange) throws IOException {
        requestCounts
                .computeIfAbsent(exchange.getRequestURI().getPath(), ignored -> new AtomicInteger())
                .incrementAndGet();
        byte[] body = responses.get(exchange.getRequestURI().getPath());
        if (body == null) {
            respond(exchange, 404, "missing".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, body);
    }

    protected int requestCount(String path) {
        return requestCounts.getOrDefault(path, new AtomicInteger()).get();
    }

    protected static MavenRepositoryClient retryingClient(int maxAttempts) {
        return new MavenRepositoryClient(
                HttpClient.newHttpClient(),
                new MavenRepositoryPathBuilder(),
                new RepositoryHttpPolicy(Duration.ofSeconds(5), maxAttempts, Duration.ZERO));
    }

    protected static void respond(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
