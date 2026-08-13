package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sh.zolt.maven.repository.RepositoryArtifactTestSupport.artifactBytes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.net.NetworkTransport;
import sh.zolt.net.ProxyConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves Zolt authenticates to a Basic-protected forward proxy. The shared {@link NetworkTransport}
 * turns credentials embedded in the proxy URL userinfo into a proxy {@link java.net.Authenticator},
 * so a {@code 407 Proxy Authentication Required} challenge is answered with a {@code
 * Proxy-Authorization} header on retry rather than the credentials being silently dropped.
 */
final class MavenRepositoryClientProxyAuthTest {
    private final CoordinateParser parser = new CoordinateParser();
    private HttpServer proxy;

    @AfterEach
    void stopProxy() {
        if (proxy != null) {
            proxy.stop(0);
        }
    }

    @Test
    void authenticatesToBasicProtectedProxyAfter407() throws Exception {
        List<String> proxyAuthorizationHeaders = new CopyOnWriteArrayList<>();
        int port = startAuthenticatingProxy(proxyAuthorizationHeaders, "<project/>");

        Function<String, String> environment =
                key -> "HTTP_PROXY".equals(key) ? "http://alice:s3cr3t@127.0.0.1:" + port : null;
        NetworkTransport transport =
                NetworkTransport.create(ProxyConfiguration.fromEnvironment(environment, key -> null), List.of());
        MavenRepositoryClient client = new MavenRepositoryClient(transport);
        Coordinate coordinate = parser.parse("com.example:widget:1.0.0");

        // The origin host is fictitious: the client dials the proxy and never resolves repo.example.
        RepositoryArtifact artifact = client.fetchPom(URI.create("http://repo.example/maven2/"), coordinate);

        assertEquals("<project/>", new String(artifactBytes(artifact), StandardCharsets.UTF_8));
        assertFalse(proxyAuthorizationHeaders.isEmpty(), "the proxy never received a Proxy-Authorization header");
        String expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertTrue(
                proxyAuthorizationHeaders.contains(expected),
                "expected the retried request to carry " + expected + " but saw " + proxyAuthorizationHeaders);
    }

    private int startAuthenticatingProxy(List<String> proxyAuthorizationHeaders, String body) throws IOException {
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> handle(exchange, proxyAuthorizationHeaders, body));
        proxy.start();
        return proxy.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange, List<String> proxyAuthorizationHeaders, String body)
            throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
        try (exchange) {
            if (header == null) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"zolt-proxy\"");
                exchange.sendResponseHeaders(407, -1);
                return;
            }
            proxyAuthorizationHeaders.add(header);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
