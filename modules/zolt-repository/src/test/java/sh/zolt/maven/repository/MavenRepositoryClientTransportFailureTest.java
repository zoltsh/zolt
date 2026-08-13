package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.CoordinateParser;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenRepositoryClientTransportFailureTest {
    private final CoordinateParser parser = new CoordinateParser();

    @TempDir
    private Path tempDir;

    @Test
    void fetchInterruptionRestoresInterruptFlagAndExplainsRetry() {
        clearInterruptFlag();
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        MavenRepositoryClient client = new MavenRepositoryClient(
                new InterruptingHttpClient(),
                new MavenRepositoryPathBuilder());

        try {
            RepositoryClientException exception = assertThrows(
                    RepositoryClientException.class,
                    () -> client.fetchPom(URI.create("https://repo.example.test/maven2/"), coordinate));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(exception.getMessage().contains("Download interrupted while fetching com.google.guava:guava:33.4.0-jre"));
            assertTrue(exception.getMessage().contains("Try again."));
        } finally {
            clearInterruptFlag();
        }
    }

    @Test
    void uploadInterruptionRestoresInterruptFlagAndExplainsRetry() throws IOException {
        clearInterruptFlag();
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Path source = tempDir.resolve("guava-33.4.0-jre.pom");
        Files.writeString(source, "<project/>");
        MavenRepositoryClient client = new MavenRepositoryClient(
                new InterruptingHttpClient(),
                new MavenRepositoryPathBuilder());

        try {
            RepositoryClientException exception = assertThrows(
                    RepositoryClientException.class,
                    () -> client.uploadPom(URI.create("https://repo.example.test/maven2/"), coordinate, source));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(exception.getMessage().contains("Upload interrupted while publishing com.google.guava:guava:33.4.0-jre"));
            assertTrue(exception.getMessage().contains("Try again."));
        } finally {
            clearInterruptFlag();
        }
    }

    @Test
    void fetchIoFailureRetriesBoundedAttemptsAndExplainsRemediation() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        FailingHttpClient httpClient = new FailingHttpClient();
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder(),
                new RepositoryHttpPolicy(Duration.ofSeconds(5), 2, Duration.ZERO));

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> client.fetchPom(URI.create("https://repo.example.test/maven2/"), coordinate));

        assertEquals(2, httpClient.sends());
        assertTrue(exception.getMessage().contains("Could not download com.google.guava:guava:33.4.0-jre"));
        assertTrue(exception.getMessage().contains("after 2 attempts"));
        assertTrue(exception.getMessage().contains("Check your network, proxy, or repository URL and try again."));
    }

    @Test
    void authorityEscapeIsRejectedBeforeCredentialsOrNetworkCanCrossOrigins() {
        FailingHttpClient httpClient = new FailingHttpClient();
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder());

        assertThrows(
                CoordinateParseException.class,
                () -> client.fetchMetadata(
                        URI.create("https://repo.company.example/maven/"),
                        "//metadata",
                        "probe",
                        Optional.of(RepositoryAuthentication.bearer("secret"))));

        assertEquals(0, httpClient.sends(), "unsafe authority must be rejected before an HTTP request exists");
    }

    @Test
    void explicitRepositoryPathsCannotEscapeTheConfiguredBase() {
        FailingHttpClient httpClient = new FailingHttpClient();
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder());

        for (String path : java.util.List.of(
                "//metadata/probe",
                "/absolute/path",
                "../../outside",
                "com/example/../outside",
                "com/example/artifact?query",
                "com/example/artifact#fragment",
                "com/example/artifact\\path",
                "com/example/%2e%2e/outside")) {
            assertThrows(
                    RepositoryClientException.class,
                    () -> client.fetchFile(
                            URI.create("https://repo.company.example/maven/"),
                            path,
                            Optional.of(RepositoryAuthentication.bearer("secret"))),
                    path);
        }

        assertEquals(0, httpClient.sends());
    }

    @Test
    void safeUnicodeCoordinatesAreEncodedWithoutChangingOriginOrBasePath() {
        String decomposed = "cafe\u0301";
        CapturingHttpClient httpClient = new CapturingHttpClient();
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder());

        assertTrue(client.fetchMetadata(
                        URI.create("https://repo.company.example/maven/"),
                        "com.example",
                        decomposed,
                        RepositoryAuthentication.none())
                .isEmpty());

        URI requestUri = httpClient.requestUri();
        assertEquals("https", requestUri.getScheme());
        assertEquals("repo.company.example", requestUri.getRawAuthority());
        assertTrue(requestUri.getRawPath().startsWith("/maven/com/example/"));
        assertTrue(requestUri.getRawPath().contains("cafe%CC%81"), requestUri::toString);
    }

    @Test
    void repositoryBaseEncodingIsPreservedWhenAddingTheRelativePath() {
        URI resolved = RepositoryArtifactUri.resolve(
                URI.create("https://repo.company.example/maven%20repository"),
                "com/example/demo/1.0.0/demo-1.0.0.pom");

        assertEquals("repo.company.example", resolved.getRawAuthority());
        assertEquals(
                "/maven%20repository/com/example/demo/1.0.0/demo-1.0.0.pom",
                resolved.getRawPath());
    }

    @Test
    void fetchRetryBackoffInterruptionRestoresInterruptFlagAndExplainsRetry() {
        clearInterruptFlag();
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        InterruptingRetryHttpClient httpClient = new InterruptingRetryHttpClient(503);
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder(),
                new RepositoryHttpPolicy(Duration.ofSeconds(5), 2, Duration.ofSeconds(5)));

        try {
            RepositoryClientException exception = assertThrows(
                    RepositoryClientException.class,
                    () -> client.fetchPom(URI.create("https://repo.example.test/maven2/"), coordinate));

            assertEquals(1, httpClient.sends());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(exception.getMessage().contains(
                    "Repository request interrupted while retrying fetching com.google.guava:guava:33.4.0-jre"));
            assertTrue(exception.getMessage().contains("after attempt 1"));
            assertTrue(exception.getMessage().contains("Try again."));
        } finally {
            clearInterruptFlag();
        }
    }

    @Test
    void uploadIoFailureRetriesBoundedAttemptsAndExplainsRemediation() throws IOException {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Path source = tempDir.resolve("guava-33.4.0-jre.pom");
        Files.writeString(source, "<project/>");
        FailingHttpClient httpClient = new FailingHttpClient();
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder(),
                new RepositoryHttpPolicy(Duration.ofSeconds(5), 2, Duration.ZERO));

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> client.uploadPom(URI.create("https://repo.example.test/maven2/"), coordinate, source));

        assertEquals(2, httpClient.sends());
        assertTrue(exception.getMessage().contains("Could not upload com.google.guava:guava:33.4.0-jre"));
        assertTrue(exception.getMessage().contains("after 2 attempts"));
        assertTrue(exception.getMessage().contains("Check your network, proxy, repository URL, and publish permissions"));
    }

    @Test
    void uploadRetryBackoffInterruptionRestoresInterruptFlagAndExplainsRetry() throws IOException {
        clearInterruptFlag();
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        Path source = tempDir.resolve("guava-33.4.0-jre.pom");
        Files.writeString(source, "<project/>");
        InterruptingRetryHttpClient httpClient = new InterruptingRetryHttpClient(429);
        MavenRepositoryClient client = new MavenRepositoryClient(
                httpClient,
                new MavenRepositoryPathBuilder(),
                new RepositoryHttpPolicy(Duration.ofSeconds(5), 2, Duration.ofSeconds(5)));

        try {
            RepositoryClientException exception = assertThrows(
                    RepositoryClientException.class,
                    () -> client.uploadPom(URI.create("https://repo.example.test/maven2/"), coordinate, source));

            assertEquals(1, httpClient.sends());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(exception.getMessage().contains(
                    "Repository request interrupted while retrying uploading com.google.guava:guava:33.4.0-jre"));
            assertTrue(exception.getMessage().contains("after attempt 1"));
            assertTrue(exception.getMessage().contains("Try again."));
        } finally {
            clearInterruptFlag();
        }
    }

    private static void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static final class InterruptingHttpClient extends DelegatingHttpClient {
        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws InterruptedException {
            throw new InterruptedException("test interrupt");
        }
    }

    private static final class FailingHttpClient extends DelegatingHttpClient {
        private final AtomicInteger sends = new AtomicInteger();

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            sends.incrementAndGet();
            throw new IOException("test network failure");
        }

        int sends() {
            return sends.get();
        }
    }

    private static final class InterruptingRetryHttpClient extends DelegatingHttpClient {
        private final int statusCode;
        private final AtomicInteger sends = new AtomicInteger();

        private InterruptingRetryHttpClient(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            sends.incrementAndGet();
            HttpResponse<T> response = response(request, statusCode, responseBodyHandler);
            Thread.currentThread().interrupt();
            return response;
        }

        int sends() {
            return sends.get();
        }
    }

    private static final class CapturingHttpClient extends DelegatingHttpClient {
        private URI requestUri;

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            requestUri = request.uri();
            return response(request, 404, responseBodyHandler);
        }

        URI requestUri() {
            return requestUri;
        }
    }

    private static <T> HttpResponse<T> response(
            HttpRequest request,
            int statusCode,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(new StaticResponseInfo(statusCode));
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
            }
        });
        subscriber.onComplete();
        T body = subscriber.getBody().toCompletableFuture().join();
        return new StaticHttpResponse<>(request, statusCode, body);
    }

    private record StaticResponseInfo(int statusCode) implements HttpResponse.ResponseInfo {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private record StaticHttpResponse<T>(
            HttpRequest request,
            int statusCode,
            T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private abstract static class DelegatingHttpClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sendAsync is not used by MavenRepositoryClient tests");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("sendAsync is not used by MavenRepositoryClient tests");
        }
    }
}
