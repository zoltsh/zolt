package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace resolve of unchanged inputs reports the lock as current and does no work; every change
 * that could make the lock wrong takes the full path instead.
 */
final class WorkspaceResolveUpToDateTest {
    private final WorkspaceResolveService service = new WorkspaceResolveService();
    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    @TempDir
    private Path tempDir;

    private HttpServer server;
    private URI baseUri;

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
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/");
        addArtifact("com.example", "lib", "1.0.0");
        addArtifact("com.example", "lib", "1.0.1");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void repeatedResolveOfUnchangedInputsResolvesNothingAndRewritesNothing() throws IOException {
        writeWorkspace("1.0.0");
        WorkspaceResolveSnapshot first = resolve();
        String committed = Files.readString(lockfilePath());
        requests.clear();

        WorkspaceResolveSnapshot second = resolve();

        assertTrue(second.resolutionSkipped());
        assertFalse(first.resolutionSkipped());
        assertEquals(first.result().resolvedCount(), second.result().resolvedCount());
        assertEquals(committed, Files.readString(lockfilePath()));
        assertEquals(Map.of(), requests);
    }

    @Test
    void resolvesAgainWhenAMemberConfigChanges() throws IOException {
        writeWorkspace("1.0.0");
        resolve();

        writeWorkspace("1.0.1");

        assertFalse(resolve().resolutionSkipped());
        assertTrue(Files.readString(lockfilePath()).contains("1.0.1"));
    }

    /** A comment is a change: the digest is over config bytes, deliberately. */
    @Test
    void resolvesAgainWhenAConfigTakesACommentOnlyEdit() throws IOException {
        writeWorkspace("1.0.0");
        resolve();
        Path member = tempDir.resolve("apps/api/zolt.toml");
        Files.writeString(member, Files.readString(member) + "\n# note\n");

        assertFalse(resolve().resolutionSkipped());
    }

    @Test
    void resolvesAgainWhenTheLockIsHandEdited() throws IOException {
        writeWorkspace("1.0.0");
        resolve();
        Files.writeString(
                lockfilePath(),
                Files.readString(lockfilePath()).replace("direct = true", "direct = false"));

        assertFalse(resolve().resolutionSkipped());
        assertTrue(Files.readString(lockfilePath()).contains("direct = true"));
    }

    /** Deleting the recorded fingerprint is the escape hatch, so it must force the full resolve. */
    @Test
    void resolvesAgainWhenTheRecordedFingerprintIsDeleted() throws IOException {
        writeWorkspace("1.0.0");
        resolve();
        String committed = Files.readString(lockfilePath());
        Files.writeString(lockfilePath(), committed.lines()
                .filter(line -> !line.startsWith("workspaceResolutionInputFingerprint = "))
                .reduce("", (left, right) -> left + right + "\n"));

        assertFalse(resolve().resolutionSkipped());
        assertEquals(committed, Files.readString(lockfilePath()));
    }

    /** The skipped resolve is also what materializes locked artifacts, so a cold cache still needs it. */
    @Test
    void resolvesAgainWhenTheCacheNoLongerHoldsALockedArtifact() throws IOException {
        writeWorkspace("1.0.0");
        resolve();
        Path lockedJar = lockedJar();
        Files.delete(lockedJar);

        assertFalse(resolve().resolutionSkipped());
        assertTrue(Files.isRegularFile(lockedJar));
    }

    @Test
    void lockedResolveStillVerifiesRatherThanReportingTheLockCurrent() throws IOException {
        writeWorkspace("1.0.0");
        resolve();

        assertFalse(service
                .resolveSnapshot(tempDir, cacheRoot(), true, ResolveOptions.defaults())
                .resolutionSkipped());
    }

    /**
     * Coverage tooling is not in the digest, so a coverage resolve against a lock that predates the
     * tooling must resolve rather than be told the lock is current.
     */
    @Test
    void coverageResolveIsNeverReportedCurrent() throws IOException {
        addArtifact("com.example", "test-lib", "1.0.0");
        addArtifact("org.jacoco", "org.jacoco.agent", "0.8.14");
        addClassifierJar("org.jacoco", "org.jacoco.agent", "0.8.14", "runtime");
        addArtifact("org.jacoco", "org.jacoco.cli", "0.8.14");
        addArtifact("org.junit.platform", "junit-platform-console", "1.11.4");
        writeWorkspaceWithTests();
        resolve();

        WorkspaceResolveSnapshot coverage = service.resolveCoverageSnapshot(
                new sh.zolt.workspace.discovery.ManifestWorkspaceLoader().load(tempDir), cacheRoot());

        assertFalse(coverage.resolutionSkipped());
        assertTrue(Files.readString(lockfilePath()).contains("org.jacoco.agent"));
    }

    private Path lockedJar() {
        String path = new ZoltLockfileReader().read(lockfilePath()).packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "lib")))
                .findFirst()
                .orElseThrow()
                .jar()
                .orElseThrow();
        return cacheRoot().resolve(path);
    }

    private WorkspaceResolveSnapshot resolve() {
        return service.resolveSnapshot(tempDir, cacheRoot(), false, ResolveOptions.defaults());
    }

    private Path cacheRoot() {
        return tempDir.resolve("cache");
    }

    private Path lockfilePath() {
        return tempDir.resolve("zolt.lock");
    }

    /** Coverage tooling only enters a resolve for a member that has test inputs. */
    private void writeWorkspaceWithTests() throws IOException {
        writeWorkspace("1.0.0");
        Path member = tempDir.resolve("apps/api/zolt.toml");
        Files.writeString(member, Files.readString(member) + """

                [dependencies.test]
                "com.example:test-lib" = "1.0.0"
                """);
    }

    private void writeWorkspace(String libVersion) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        Path member = tempDir.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.acme"
                java = 21

                [dependencies]
                "com.example:lib" = "%s"
                """.formatted(libVersion));
    }

    private void addClassifierJar(String groupId, String artifactId, String version, String classifier) {
        responses.put(
                "/maven2/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                        + artifactId + "-" + version + "-" + classifier + ".jar",
                new byte[] {0x50, 0x4b, 0x03, 0x04});
    }

    private void addArtifact(String groupId, String artifactId, String version) {
        String base = "/maven2/" + groupId.replace('.', '/') + "/" + artifactId
                + "/" + version + "/" + artifactId + "-" + version;
        responses.put(base + ".pom", """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version).getBytes(StandardCharsets.UTF_8));
        responses.put(base + ".jar", new byte[] {0x50, 0x4b, 0x03, 0x04});
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        byte[] body = responses.get(path);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
