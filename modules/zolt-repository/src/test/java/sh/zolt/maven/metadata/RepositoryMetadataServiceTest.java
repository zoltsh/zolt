package sh.zolt.maven.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryAuthentication;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RepositoryMetadataServiceTest {
    @TempDir
    Path cacheRoot;

    private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
    private final Map<String, Integer> forcedStatus = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private HttpServer server;
    private URI origin;
    private MetadataCache cache;

    @BeforeEach
    void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
            return;
        }
        server.createContext("/", this::handle);
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        cache = new MetadataCache(cacheRoot);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unionsVersionsAcrossRepositoriesWithSourceAttribution() {
        putListing("alpha", "com.example", "lib", "1.0.0", "2.0.0");
        putListing("zeta", "com.example", "lib", "2.0.0", "3.0.0");

        MetadataDiscovery discovery =
                service().discover(List.of(access("alpha"), access("zeta")), "com.example", "lib", false);

        assertTrue(discovery.resolved());
        assertEquals(List.of("1.0.0", "2.0.0", "3.0.0"), discovery.versions());
        assertEquals(Optional.of("alpha"), discovery.source("1.0.0"));
        assertEquals(Optional.of("alpha"), discovery.source("2.0.0"));
        assertEquals(Optional.of("zeta"), discovery.source("3.0.0"));
        assertTrue(discovery.notes().isEmpty());
    }

    @Test
    void sourceIsFirstRepositoryInQueryOrder() {
        putListing("alpha", "com.example", "lib", "2.0.0");
        putListing("zeta", "com.example", "lib", "2.0.0");

        MetadataDiscovery discovery =
                service().discover(List.of(access("zeta"), access("alpha")), "com.example", "lib", false);

        assertEquals(Optional.of("zeta"), discovery.source("2.0.0"));
    }

    @Test
    void skipsRepositoriesReturningNotFound() {
        putListing("alpha", "com.example", "lib", "1.0.0");

        MetadataDiscovery discovery =
                service().discover(List.of(access("alpha"), access("zeta")), "com.example", "lib", false);

        assertEquals(List.of("1.0.0"), discovery.versions());
        assertTrue(discovery.notes().isEmpty());
    }

    @Test
    void missingEverywhereIsUnknownWithoutError() {
        MetadataDiscovery discovery =
                service().discover(List.of(access("alpha"), access("zeta")), "com.example", "absent", false);

        assertFalse(discovery.resolved());
        assertTrue(discovery.versions().isEmpty());
        assertTrue(discovery.notes().isEmpty());
    }

    @Test
    void fallsBackToCacheWithStalenessNoteOnTransientFailure() {
        cache.write(access("flaky"), "com.example", "lib", listing("1.0.0", "2.0.0"));
        forcedStatus.put("/flaky/com/example/lib/maven-metadata.xml", 503);

        MetadataDiscovery discovery =
                service().discover(List.of(access("flaky")), "com.example", "lib", false);

        assertTrue(discovery.resolved());
        assertEquals(List.of("1.0.0", "2.0.0"), discovery.versions());
        assertEquals(1, discovery.notes().size());
        assertTrue(discovery.notes().getFirst().contains("Using cached version listing"));
        assertTrue(discovery.notes().getFirst().contains("fresh fetch failed"));
    }

    @Test
    void offlineUsesCacheOnlyWithoutNetwork() {
        cache.write(access("central"), "com.example", "lib", listing("1.0.0"));

        MetadataDiscovery discovery =
                service().discover(List.of(access("central")), "com.example", "lib", true);

        assertTrue(discovery.resolved());
        assertEquals(List.of("1.0.0"), discovery.versions());
        assertEquals(0, requestCount("/central/com/example/lib/maven-metadata.xml"));
    }

    @Test
    void offlineWithoutCacheIsUnknownWithNote() {
        MetadataDiscovery discovery =
                service().discover(List.of(access("central")), "com.example", "lib", true);

        assertFalse(discovery.resolved());
        assertEquals(1, discovery.notes().size());
        assertTrue(discovery.notes().getFirst().contains("Offline: no cached version listing"));
    }

    @Test
    void onlineAlwaysRefetchesWithoutTtl() {
        putListing("central", "com.example", "lib", "1.0.0");
        RepositoryMetadataService service = service();

        service.discover(List.of(access("central")), "com.example", "lib", false);
        service.discover(List.of(access("central")), "com.example", "lib", false);

        assertEquals(2, requestCount("/central/com/example/lib/maven-metadata.xml"));
    }

    @Test
    void onlineFetchPopulatesCacheForLaterOfflineUse() {
        putListing("central", "com.example", "lib", "1.0.0", "1.1.0");
        RepositoryMetadataService service = service();

        service.discover(List.of(access("central")), "com.example", "lib", false);
        MetadataDiscovery offline =
                service.discover(List.of(access("central")), "com.example", "lib", true);

        assertEquals(List.of("1.0.0", "1.1.0"), offline.versions());
    }

    @Test
    void offlineCacheDoesNotCrossSameIdWithDifferentRepositoryUrls() {
        putListingAt("company-a", "com.example", "lib", "1.0.0");
        RepositoryMetadataService service = service();
        service.discover(List.of(access("private", "company-a")), "com.example", "lib", false);

        MetadataDiscovery companyB =
                service.discover(List.of(access("private", "company-b")), "com.example", "lib", true);

        assertFalse(companyB.resolved());
        assertTrue(companyB.versions().isEmpty());
    }

    @Test
    void transientFailureDoesNotFallBackAcrossSameIdWithDifferentRepositoryUrls() {
        putListingAt("company-a", "com.example", "lib", "1.0.0");
        forcedStatus.put("/company-b/com/example/lib/maven-metadata.xml", 503);
        RepositoryMetadataService service = service();
        service.discover(List.of(access("private", "company-a")), "com.example", "lib", false);

        MetadataDiscovery companyB =
                service.discover(List.of(access("private", "company-b")), "com.example", "lib", false);

        assertFalse(companyB.resolved());
        assertTrue(companyB.notes().getFirst().contains("Could not fetch version listing"), companyB.notes().toString());
    }

    @Test
    void cacheIdentityDoesNotCollapseRepositoryIdsThatSanitizeTheSame() {
        putListingAt("shared", "com.example", "lib", "1.0.0");
        RepositoryMetadataService service = service();
        service.discover(List.of(access("team/a", "shared")), "com.example", "lib", false);

        MetadataDiscovery other =
                service.discover(List.of(access("team?a", "shared")), "com.example", "lib", true);

        assertFalse(other.resolved());
    }

    @Test
    void authenticatedRepositoryListingsAreNotReusedAcrossCredentialContexts() {
        putListingAt("secured", "com.example", "lib", "1.0.0");
        RepositoryMetadataService service = service();
        service.discover(
                List.of(authenticatedAccess("private", "secured", "company-a-token")),
                "com.example",
                "lib",
                false);

        MetadataDiscovery companyB = service.discover(
                List.of(authenticatedAccess("private", "secured", "company-b-token")),
                "com.example",
                "lib",
                true);

        assertFalse(companyB.resolved());
        assertTrue(companyB.versions().isEmpty());
    }

    private RepositoryMetadataService service() {
        return new RepositoryMetadataService(new MavenRepositoryClient(), cache);
    }

    private RepositoryAccess access(String repositoryId) {
        return access(repositoryId, repositoryId);
    }

    private RepositoryAccess access(String repositoryId, String remoteName) {
        return new RepositoryAccess(repositoryId, origin.resolve(remoteName + "/"), Optional.empty());
    }

    private RepositoryAccess authenticatedAccess(String repositoryId, String remoteName, String token) {
        return new RepositoryAccess(
                repositoryId,
                origin.resolve(remoteName + "/"),
                Optional.of(RepositoryAuthentication.bearer(token)));
    }

    private void putListing(String repositoryId, String groupId, String artifactId, String... versions) {
        putListingAt(repositoryId, groupId, artifactId, versions);
    }

    private void putListingAt(String remoteName, String groupId, String artifactId, String... versions) {
        bodies.put(metadataPath(remoteName, groupId, artifactId), listing(versions));
    }

    private static String metadataPath(String repositoryId, String groupId, String artifactId) {
        return "/" + repositoryId + "/" + groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
    }

    private static byte[] listing(String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        xml.append("</versions></versioning></metadata>");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    private int requestCount(String path) {
        return counts.getOrDefault(path, new AtomicInteger()).get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        counts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        Integer forced = forcedStatus.get(path);
        if (forced != null) {
            respond(exchange, forced, "forced".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = bodies.get(path);
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
}
