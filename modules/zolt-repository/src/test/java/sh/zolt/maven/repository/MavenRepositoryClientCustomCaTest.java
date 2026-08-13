package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.maven.repository.RepositoryArtifactTestSupport.artifactBytes;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.net.NetworkTransport;
import sh.zolt.net.ProxyConfiguration;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenRepositoryClientCustomCaTest {
    private final CoordinateParser parser = new CoordinateParser();
    private HttpsServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void trustsSelfSignedServerWhenCaBundleConfigured(@TempDir Path directory) throws Exception {
        URI baseUri = startHttpsServer(directory);
        Path pem = directory.resolve("ca.pem");
        NetworkTransport transport = NetworkTransport.create(noProxy(), List.of(pem));
        MavenRepositoryClient client = new MavenRepositoryClient(transport);
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        RepositoryArtifact artifact = client.fetchPom(baseUri, coordinate);

        assertTrue(new String(artifactBytes(artifact), StandardCharsets.UTF_8).contains("<project/>"));
    }

    @Test
    void rejectsSelfSignedServerWithDefaultTrust(@TempDir Path directory) throws Exception {
        URI baseUri = startHttpsServer(directory);
        MavenRepositoryClient client = new MavenRepositoryClient(NetworkTransport.direct());
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        RepositoryClientException exception = assertThrows(
                RepositoryClientException.class,
                () -> client.fetchPom(baseUri, coordinate));

        assertTrue(exception.getMessage().contains("Could not download com.google.guava:guava:33.4.0-jre"));
    }

    private URI startHttpsServer(Path directory) throws Exception {
        Optional<SelfSignedCertificateFixture> fixture = SelfSignedCertificateFixture.generate(directory);
        assumeTrue(fixture.isPresent(), "keytool is required to generate the self-signed HTTPS fixture");
        SSLContext serverContext = serverSslContext(fixture.orElseThrow().keyStore());
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/maven2/", exchange -> {
            byte[] body = "<project/>".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return URI.create("https://localhost:" + server.getAddress().getPort() + "/maven2/");
    }

    private static SSLContext serverSslContext(Path keyStorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, SelfSignedCertificateFixture.PASSWORD);
        }
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, SelfSignedCertificateFixture.PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }

    private static ProxyConfiguration noProxy() {
        return ProxyConfiguration.fromEnvironment(key -> null, key -> null);
    }
}
