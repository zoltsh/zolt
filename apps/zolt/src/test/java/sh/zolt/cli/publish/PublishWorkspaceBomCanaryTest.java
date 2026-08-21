package sh.zolt.cli.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.cli.CliTestSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The trust piece: publish the {@code platform-family} workspace (acme-core, acme-http depending on
 * it, acme-bom with members = true, uniform 1.0.0) to a local repository, then have
 * {@code platform-family-consumer} resolve {@code acme-http} version-less through the BOM.
 *
 * <p>Because {@code java.net.http.HttpClient} cannot speak {@code file://}, the "file repo" is a
 * round-trip HTTP server whose GET serves exactly what PUT stored — a genuine publish then resolve
 * loop. Asserts: resolution succeeds version-less; the BOM supplies 1.0.0 (platform-managed, not a
 * direct pin); the consumer lock records the fixture repo; the published acme-bom POM byte-equals the
 * golden; and acme-http's POM carries the inter-member dependency on acme-core@1.0.0.
 */
final class PublishWorkspaceBomCanaryTest {
    @Test
    void publishesTheFamilyThenResolvesVersionLessThroughTheBom(@TempDir Path tempDir) throws IOException {
        RoundTripRepository repository = RoundTripRepository.start();
        try {
            Path family = tempDir.resolve("platform-family");
            Path consumer = tempDir.resolve("platform-family-consumer");
            copyTree(exampleRoot().resolve("platform-family"), family);
            copyTree(exampleRoot().resolve("platform-family-consumer"), consumer);
            String repositoryUrl = repository.baseUri();
            // Point every member's [publish] and the consumer's repository at the fixture repo.
            for (String member : new String[] {"acme-core", "acme-http", "acme-bom"}) {
                rewrite(family.resolve(member).resolve("zolt.toml"),
                        "https://repo.example.test/releases", repositoryUrl);
            }
            rewrite(consumer.resolve("zolt.toml"), "https://fixture.example.test/repo", repositoryUrl);

            Path cache = tempDir.resolve("cache");
            run(family, cache, "resolve", "--workspace");
            run(family, cache, "build", "--workspace");
            run(family, cache, "package", "--workspace");
            run(family, cache, "publish", "--workspace");

            // The whole family landed in the fixture repo.
            assertTrue(repository.has("/maven2/com/acme/acme-core/1.0.0/acme-core-1.0.0.jar"));
            assertTrue(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar"));
            assertTrue(repository.has("/maven2/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0.pom"));

            // The published BOM POM byte-equals the golden.
            String publishedBom = repository.text("/maven2/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0.pom");
            assertEquals(golden("platform-family-acme-bom.pom.xml"), publishedBom);

            // acme-http's POM carries the inter-member dependency on acme-core@1.0.0.
            String publishedHttpPom = repository.text("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.pom");
            assertTrue(publishedHttpPom.contains("<artifactId>acme-core</artifactId>"));
            assertTrue(publishedHttpPom.contains("<version>1.0.0</version>"));

            // The consumer resolves acme-http version-less via the BOM platform.
            CliTestSupport.CommandResult resolve = executeIn(consumer, cache, "resolve");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            String lock = Files.readString(consumer.resolve("zolt.lock"));
            assertTrue(lock.contains("com.acme:acme-http"), lock);
            assertTrue(lock.contains("com.acme:acme-core"), lock);
            assertTrue(lock.contains("1.0.0"), lock);
            // The BOM supplies acme-http's version (platform-managed provenance), not a direct pin.
            assertTrue(lock.contains("managed-version") && lock.contains("acme-bom"), lock);
            // The fixture repository (the only configured repo the artifacts came from) is recorded as
            // the resolution source, and acme-http is a direct dependency resolved transitively to acme-core.
            assertTrue(lock.contains("source = "), lock);
            assertTrue(lock.contains("dependencies = [\"com.acme:acme-core:1.0.0:jar:compile\"]"), lock);
        } finally {
            repository.close();
        }
    }

    @Test
    void publishesSourcesJavadocAndSbomForEveryJarMemberButNotTheBom(@TempDir Path tempDir) throws IOException {
        RoundTripRepository repository = RoundTripRepository.start();
        try {
            Path family = tempDir.resolve("platform-family");
            copyTree(exampleRoot().resolve("platform-family"), family);
            String repositoryUrl = repository.baseUri();
            for (String member : new String[] {"acme-core", "acme-http", "acme-bom"}) {
                rewrite(family.resolve(member).resolve("zolt.toml"),
                        "https://repo.example.test/releases", repositoryUrl);
            }
            // Enable sources + javadoc on the jar members so packaging records them as supplementals.
            for (String member : new String[] {"acme-core", "acme-http"}) {
                rewrite(family.resolve(member).resolve("zolt.toml"),
                        "[publish]",
                        "[package]\nsources = true\njavadoc = true\n\n[publish]");
            }

            Path cache = tempDir.resolve("cache");
            run(family, cache, "resolve", "--workspace");
            run(family, cache, "build", "--workspace");
            run(family, cache, "package", "--workspace");
            run(family, cache, "publish", "--workspace", "--sbom");

            // Finding #1 + #4 end-to-end: every jar member's sources, javadoc, and CycloneDX SBOM are
            // planned from the package-evidence manifest and uploaded alongside the jar and POM.
            for (String member : new String[] {"acme-core", "acme-http"}) {
                String base = "/maven2/com/acme/" + member + "/1.0.0/" + member + "-1.0.0";
                assertTrue(repository.has(base + ".jar"), member + " jar");
                assertTrue(repository.has(base + "-sources.jar"), member + " sources jar");
                assertTrue(repository.has(base + "-javadoc.jar"), member + " javadoc jar");
                assertTrue(repository.has(base + "-cyclonedx.json"), member + " sbom");
                assertTrue(repository.has(base + "-cyclonedx.json.sha256"), member + " sbom checksum");
                assertTrue(repository.has(base + ".pom"), member + " pom");
            }
            // The BOM has no resolved graph: it never gets an SBOM (design), only its POM.
            assertTrue(repository.has("/maven2/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0.pom"));
            assertFalse(repository.has("/maven2/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0-cyclonedx.json"));
        } finally {
            repository.close();
        }
    }

    @Test
    void publishedMemberSbomRendersTheWorkspaceSiblingClosureNotJustAFile(@TempDir Path tempDir) throws IOException {
        RoundTripRepository repository = RoundTripRepository.start();
        try {
            Path family = tempDir.resolve("platform-family");
            copyTree(exampleRoot().resolve("platform-family"), family);
            String repositoryUrl = repository.baseUri();
            for (String member : new String[] {"acme-core", "acme-http", "acme-bom"}) {
                rewrite(family.resolve(member).resolve("zolt.toml"),
                        "https://repo.example.test/releases", repositoryUrl);
            }

            Path cache = tempDir.resolve("cache");
            run(family, cache, "resolve", "--workspace");
            run(family, cache, "build", "--workspace");
            run(family, cache, "package", "--workspace");
            run(family, cache, "publish", "--workspace", "--sbom");

            // Content, not just presence: acme-http depends on the workspace sibling acme-core, so its
            // published SBOM must render acme-core as a first-party component AND carry the
            // root -> sibling edge. (The POM-shaped projection that once fed this path could only emit
            // declared directs with empty hashes/edges; the SBOM projection carries the real closure.)
            String sbom = repository.text("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0-cyclonedx.json");
            assertTrue(sbom.contains("\"bomFormat\": \"CycloneDX\""), sbom);
            assertTrue(sbom.contains("\"name\": \"acme-core\""), sbom);
            assertTrue(sbom.contains("pkg:maven/com.acme/acme-core@1.0.0"), sbom);
            // The root acme-http depends on exactly the sibling — this dependsOn edge is that root -> sibling.
            assertTrue(sbom.contains("\"dependsOn\": [\"pkg:maven/com.acme/acme-core@1.0.0"), sbom);
        } finally {
            repository.close();
        }
    }

    private static void run(Path projectRoot, Path cache, String... command) {
        CliTestSupport.CommandResult result = executeIn(projectRoot, cache, command);
        assertEquals(0, result.exitCode(), String.join(" ", command) + " failed:\n" + result.stdout() + result.stderr());
    }

    private static CliTestSupport.CommandResult executeIn(Path projectRoot, Path cache, String... command) {
        String[] args = new String[command.length + 4];
        System.arraycopy(command, 0, args, 0, command.length);
        args[command.length] = "--cwd";
        args[command.length + 1] = projectRoot.toString();
        args[command.length + 2] = "--cache-root";
        args[command.length + 3] = cache.toString();
        return CliTestSupport.execute(args);
    }

    private static void rewrite(Path file, String from, String to) throws IOException {
        Files.writeString(file, Files.readString(file).replace(from, to));
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static String golden(String name) throws IOException {
        return new String(
                PublishWorkspaceBomCanaryTest.class.getResourceAsStream("/golden/" + name).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static Path exampleRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("examples/platform-family"))) {
                return current.resolve("examples");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate examples/ from " + Path.of("").toAbsolutePath());
    }

    /** A Maven repository whose GET serves exactly what PUT stored. */
    private static final class RoundTripRepository implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();
        private final String baseUri;

        private RoundTripRepository(HttpServer server) {
            this.server = server;
            this.baseUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/";
        }

        static RoundTripRepository start() throws IOException {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
                throw exception;
            }
            RoundTripRepository repository = new RoundTripRepository(server);
            server.createContext("/", repository::handle);
            server.start();
            return repository;
        }

        String baseUri() {
            return baseUri;
        }

        boolean has(String path) {
            return store.containsKey(path);
        }

        String text(String path) {
            return new String(store.get(path), StandardCharsets.UTF_8);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                store.put(path, exchange.getRequestBody().readAllBytes());
                respond(exchange, 201, new byte[0]);
                return;
            }
            byte[] body = store.get(path);
            if (body == null) {
                respond(exchange, 404, "missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, body);
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
