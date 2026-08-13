package sh.zolt.cli.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import sh.zolt.build.cache.BuildCacheKey;
import sh.zolt.build.cache.BuildCacheScope;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.cache.RemoteBuildCacheClient;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.maven.metadata.MetadataCache;
import sh.zolt.maven.metadata.MetadataDiscovery;
import sh.zolt.maven.metadata.RepositoryMetadataService;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.maven.repository.RepositoryClientException;
import sh.zolt.net.NetworkTransport;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared-transport integration proof the enterprise review demands: WITH {@code [network].caBundle}
 * configured, the SINGLE {@link NetworkTransport} built by the composition root ({@link
 * CommandNetwork#transport(Path)}) is the one that serves every outbound surface owned here — artifact
 * resolution, metadata discovery (the {@code zolt outdated}/{@code zolt update} path), single-project
 * publish upload, and remote build-cache GET/PUT — against one self-signed HTTPS fixture. Each surface
 * is also shown to FAIL with a bare {@link NetworkTransport#direct()} client that lacks the custom CA,
 * which is exactly the split-brain regression this fix closes. (Workspace publish and Central Portal are
 * covered by the parallel publishing work.)
 */
final class SharedNetworkTransportIntegrationTest {
    private static final BuildCacheKey CACHE_KEY =
            BuildCacheKey.of(BuildCacheScope.MAIN, "shared-transport-inputs", "21");
    private static final String POM = "<project/>";
    private static final String METADATA = """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <versioning>
                <versions>
                  <version>1.0.0</version>
                  <version>1.1.0</version>
                </versions>
              </versioning>
            </metadata>
            """;

    private final CoordinateParser parser = new CoordinateParser();
    private HttpsServer server;
    private final Map<String, byte[]> blobStore = new ConcurrentHashMap<>();

    @TempDir
    private Path work;

    @BeforeEach
    void startServer() throws Exception {
        assumeTrue(!hasAmbientNetworkOverride(),
                "ambient HTTP(S)_PROXY or ZOLT_CA_BUNDLE would divert or override the localhost fixture");
        Path pki = Files.createDirectories(work.resolve("pki"));
        Optional<Path> keyStore = generateSelfSignedKeyStore(pki);
        assumeTrue(keyStore.isPresent(), "keytool is required to generate the self-signed HTTPS fixture");

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext(keyStore.orElseThrow())));
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void oneConfiguredTransportServesEveryOutboundSurface() throws Exception {
        // The exact composition-root seam the CLI uses, reading [network].caBundle from a config file.
        NetworkTransport transport = CommandNetwork.transport(writeCaBundleConfig());

        URI repository = baseUri("maven2/");
        URI cache = baseUri("cache/");

        // Surface 1: artifact resolution (zolt resolve / the build path).
        MavenRepositoryClient client = new MavenRepositoryClient(transport);
        try (RepositoryArtifact pom = client.fetchPom(repository, coordinate())) {
            assertTrue(
                    Files.readString(pom.temporaryPath(), StandardCharsets.UTF_8).contains("<project"),
                    "resolution over TLS");
        }

        // Surface 2: metadata discovery — the zolt outdated / zolt update path.
        RepositoryMetadataService discovery = new RepositoryMetadataService(
                new MavenRepositoryClient(transport), new MetadataCache(work.resolve("metadata-cache")));
        MetadataDiscovery discovered = discovery.discover(
                List.of(new RepositoryAccess("central", repository, Optional.empty())), "com.example", "widget", false);
        assertTrue(discovered.versions().contains("1.1.0"), "metadata discovery over TLS: " + discovered.versions());

        // Surface 3: single-project publish upload (the shared upload path, no publish internals touched).
        Path artifact = Files.writeString(work.resolve("widget-1.0.0.jar"), "jar-bytes");
        new MavenRepositoryClient(transport)
                .uploadFile(repository, "com/example/widget/1.0.0/widget-1.0.0.jar", artifact, Optional.empty());
        assertTrue(blobStore.containsKey("/maven2/com/example/widget/1.0.0/widget-1.0.0.jar"), "publish upload over TLS");

        // Surface 4: remote build-cache PUT then GET, through the same transport's HttpClient.
        BuildCacheService producer = BuildCacheService.create(
                new BuildCacheSettings(true, work.resolve("cache-producer"), 0L),
                Optional.of(new RemoteBuildCacheClient(transport.newHttpClient(), cache, Optional.empty(), true)),
                "test");
        producer.store(CACHE_KEY, writeClassOutput(work.resolve("out-producer")));
        assertTrue(blobStore.containsKey("/cache/" + CACHE_KEY.shardedPath(".zbc")), "remote cache PUT over TLS");

        BuildCacheService consumer = BuildCacheService.create(
                new BuildCacheSettings(true, work.resolve("cache-consumer"), 0L),
                Optional.of(new RemoteBuildCacheClient(transport.newHttpClient(), cache, Optional.empty(), false)),
                "test");
        consumer.restore(CACHE_KEY, work.resolve("out-consumer"));
        assertTrue(
                Files.exists(work.resolve("out-consumer/com/example/A.class")),
                "remote cache GET over TLS restored the entry");
    }

    @Test
    void everyOutboundSurfaceFailsWithABareTransportLackingTheCa() throws Exception {
        // Seed the cache blob over a configured transport so the only reason a bare client misses is TLS.
        NetworkTransport configured = CommandNetwork.transport(writeCaBundleConfig());
        BuildCacheService seed = BuildCacheService.create(
                new BuildCacheSettings(true, work.resolve("cache-seed"), 0L),
                Optional.of(new RemoteBuildCacheClient(configured.newHttpClient(), baseUri("cache/"), Optional.empty(), true)),
                "test");
        seed.store(CACHE_KEY, writeClassOutput(work.resolve("out-seed")));

        NetworkTransport bare = NetworkTransport.direct();
        URI repository = baseUri("maven2/");

        // Surface 1: resolution fails (untrusted certificate).
        assertThrows(
                RepositoryClientException.class,
                () -> new MavenRepositoryClient(bare).fetchPom(repository, coordinate()));

        // Surface 2: metadata discovery degrades to no versions rather than reaching the repository.
        MetadataDiscovery discovered = new RepositoryMetadataService(
                        new MavenRepositoryClient(bare), new MetadataCache(work.resolve("metadata-cache-bare")))
                .discover(List.of(new RepositoryAccess("central", repository, Optional.empty())),
                        "com.example", "widget", false);
        assertTrue(discovered.versions().isEmpty(), "bare transport must not discover versions");

        // Surface 3: publish upload fails (untrusted certificate).
        Path artifact = Files.writeString(work.resolve("widget-bare.jar"), "jar-bytes");
        assertThrows(
                RepositoryClientException.class,
                () -> new MavenRepositoryClient(bare)
                        .uploadFile(repository, "com/example/widget/1.0.0/widget-1.0.0.jar", artifact, Optional.empty()));

        // Surface 4: remote cache GET is a miss (the swallowed TLS failure), so nothing restores.
        BuildCacheService bareConsumer = BuildCacheService.create(
                new BuildCacheSettings(true, work.resolve("cache-bare"), 0L),
                Optional.of(new RemoteBuildCacheClient(bare.newHttpClient(), baseUri("cache/"), Optional.empty(), false)),
                "test");
        bareConsumer.restore(CACHE_KEY, work.resolve("out-bare"));
        assertFalse(Files.exists(work.resolve("out-bare/com/example/A.class")), "bare transport must not restore");
    }

    private Path writeCaBundleConfig() throws Exception {
        Path config = work.resolve("config.toml");
        Files.writeString(config, "version = 1\n\n[network]\ncaBundle = \"" + work.resolve("pki/ca.pem") + "\"\n");
        return config;
    }

    private Coordinate coordinate() {
        return parser.parse("com.example:widget:1.0.0");
    }

    private URI baseUri(String path) {
        return URI.create("https://localhost:" + server.getAddress().getPort() + "/" + path);
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        try (exchange) {
            if ("PUT".equals(exchange.getRequestMethod())) {
                blobStore.put(path, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(201, -1);
                return;
            }
            byte[] body = responseFor(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private byte[] responseFor(String path) {
        if (path.endsWith("maven-metadata.xml")) {
            return METADATA.getBytes(StandardCharsets.UTF_8);
        }
        if (path.endsWith(".pom")) {
            return POM.getBytes(StandardCharsets.UTF_8);
        }
        return blobStore.get(path);
    }

    private static Path writeClassOutput(Path outputDirectory) throws java.io.IOException {
        Path classFile = outputDirectory.resolve("com/example/A.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "cached-class-bytes");
        return outputDirectory;
    }

    private static boolean hasAmbientNetworkOverride() {
        for (String name : List.of("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ZOLT_CA_BUNDLE")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Path> generateSelfSignedKeyStore(Path directory) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.isExecutable(keytool)) {
            return Optional.empty();
        }
        Path keyStore = directory.resolve("server.p12");
        Path pem = directory.resolve("ca.pem");
        boolean generated = run(
                keytool.toString(), "-genkeypair", "-alias", "zolt-shared", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", "changeit", "-keypass", "changeit");
        boolean exported = generated && run(
                keytool.toString(), "-exportcert", "-rfc", "-alias", "zolt-shared",
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-file", pem.toString());
        return exported ? Optional.of(keyStore) : Optional.empty();
    }

    private static boolean run(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return process.waitFor() == 0;
    }

    private static SSLContext serverSslContext(Path keyStorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }
}
