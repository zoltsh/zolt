package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.maven.repository.RepositoryArtifactTestSupport.artifactBytes;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.maven.Coordinate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class MavenRepositoryClientHttpBehaviorTest extends MavenRepositoryClientTestSupport {
    @Test
    void missingArtifactMessageIsActionable() {
        Coordinate coordinate = parser.parse("com.google.guava:missing:1.0.0");

        RepositoryMissingArtifactException exception = assertThrows(
                RepositoryMissingArtifactException.class,
                () -> client.fetchPom(baseUri, coordinate));

        assertTrue(exception.getMessage().contains("Could not find com.google.guava:missing:1.0.0"));
        assertEquals(
                1,
                requestCount("/maven2/com/google/guava/missing/1.0.0/missing-1.0.0.pom"));
    }

    @Test
    void fetchAcceptsRepositoryBaseUriWithoutTrailingSlash() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/noslash/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "<project/>".getBytes(StandardCharsets.UTF_8));
        });
        URI noSlashBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/noslash");

        RepositoryArtifact artifact = client.fetchPom(noSlashBaseUri, coordinate);

        assertEquals(
                "/noslash/com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom",
                path.get());
        assertEquals(
                noSlashBaseUri.resolve("/noslash/com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom"),
                artifact.source());
    }

    @Test
    void networkFailureMessageIsActionable() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        URI unreachable = URI.create("http://127.0.0.1:1/maven2/");

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> client.fetchPom(unreachable, coordinate));

        assertTrue(exception.getMessage().contains("Could not download com.google.guava:guava:33.4.0-jre"));
        assertTrue(exception.getMessage().contains("Check your network, proxy, or repository URL and try again."));
    }

    @Test
    void nonSuccessStatusMessageIsActionable() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        server.createContext("/error/", exchange -> respond(exchange, 500, "nope".getBytes(StandardCharsets.UTF_8)));
        URI errorBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/error/");

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> client.fetchPom(errorBaseUri, coordinate));

        assertTrue(exception.getMessage().contains("Repository returned HTTP 500"));
        assertTrue(exception.getMessage().contains("Try again or check the repository URL."));
    }

    @Test
    void transientStatusIsRetriedUntilSuccess() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/flaky/", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                respond(exchange, 503, "slow down".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "<project/>".getBytes(StandardCharsets.UTF_8));
        });
        URI flakyBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/flaky/");
        MavenRepositoryClient retryingClient = retryingClient(3);

        RepositoryArtifact artifact = retryingClient.fetchPom(flakyBaseUri, coordinate);

        assertEquals(2, attempts.get());
        assertTrue(new String(artifactBytes(artifact), StandardCharsets.UTF_8).contains("<project/>"));
    }

    @Test
    void transientStatusFailsAfterBoundedAttempts() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/always-flaky/", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 429, "too many".getBytes(StandardCharsets.UTF_8));
        });
        URI flakyBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/always-flaky/");
        MavenRepositoryClient retryingClient = retryingClient(2);

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> retryingClient.fetchPom(flakyBaseUri, coordinate));

        assertEquals(2, attempts.get());
        assertTrue(exception.getMessage().contains("Repository returned HTTP 429"));
        assertTrue(exception.getMessage().contains("after 2 attempts"));
    }

    @Test
    void permanentClientStatusIsNotRetried() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/bad-request/", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 400, "bad".getBytes(StandardCharsets.UTF_8));
        });
        URI badRequestBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bad-request/");
        MavenRepositoryClient retryingClient = retryingClient(3);

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> retryingClient.fetchPom(badRequestBaseUri, coordinate));

        assertEquals(1, attempts.get());
        assertTrue(exception.getMessage().contains("Repository returned HTTP 400"));
    }

    @Test
    void sendsBasicAuthenticationHeaderWhenConfigured() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/auth/", exchange -> {
            attempts.incrementAndGet();
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("zolt-user:zolt-secret".getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "unauthorized".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "<project/>".getBytes(StandardCharsets.UTF_8));
        });
        URI authBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/auth/");

        RepositoryArtifact artifact = client.fetchPom(
                authBaseUri,
                coordinate,
                Optional.of(new RepositoryAuthentication("zolt-user", "zolt-secret")));

        assertEquals(1, attempts.get());
        assertTrue(new String(artifactBytes(artifact), StandardCharsets.UTF_8).contains("<project/>"));
    }

    @Test
    void sendsBearerAuthorizationHeaderWhenConfigured() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/bearer/", exchange -> {
            attempts.incrementAndGet();
            if (!"Bearer pat-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "unauthorized".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "<project/>".getBytes(StandardCharsets.UTF_8));
        });
        URI bearerBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bearer/");

        RepositoryArtifact artifact = client.fetchPom(
                bearerBaseUri,
                coordinate,
                Optional.of(RepositoryAuthentication.bearer("pat-token")));

        assertEquals(1, attempts.get());
        assertTrue(new String(artifactBytes(artifact), StandardCharsets.UTF_8).contains("<project/>"));
    }

    @Test
    void authenticationFailureMessageIsActionableAndDoesNotLeakCredentials() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/unauthorized/", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 401, "unauthorized".getBytes(StandardCharsets.UTF_8));
        });
        URI unauthorizedBaseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/unauthorized/");

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> client.fetchPom(
                        unauthorizedBaseUri,
                        coordinate,
                        Optional.of(new RepositoryAuthentication("zolt-user", "zolt-secret"))));

        assertEquals(1, attempts.get());
        assertTrue(exception.getMessage().contains("Repository returned HTTP 401 while fetching"));
        assertTrue(exception.getMessage().contains("Try again or check the repository URL."));
        assertTrue(exception.getMessage().contains("com.google.guava:guava:33.4.0-jre"));
        assertTrue(exception.getMessage().contains("/unauthorized/"));
        assertTrue(exception.getMessage().contains("guava-33.4.0-jre.pom"));
        assertFalse(exception.getMessage().contains("zolt-user"));
        assertFalse(exception.getMessage().contains("zolt-secret"));
    }
}
