package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An ordinary workspace resolve records the fingerprint the freshness gate later reads, and a locked
 * resolve keeps working on a lock written before the fingerprint existed.
 */
final class WorkspaceResolveServiceFingerprintTest {
    private final WorkspaceResolveService service = new WorkspaceResolveService();
    private final ZoltLockfileReader reader = new ZoltLockfileReader();
    private final Map<String, byte[]> responses = new HashMap<>();

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
    void ordinaryResolveRecordsTheFingerprintItsInputsAndItsLockProduce() throws IOException {
        writeWorkspace("1.0.0");

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        String committed = Files.readString(result.lockfilePath());
        assertEquals(
                WorkspaceResolutionInputFingerprint.fingerprint(
                        new ManifestWorkspaceLoader().load(tempDir), committed),
                reader.read(committed).workspaceResolutionInputFingerprint());
        assertTrue(reader.read(committed).workspaceResolutionInputFingerprint().isPresent());
    }

    /** The fingerprint covers the lock that carries it, so it must not perturb its own bytes. */
    @Test
    void repeatedResolveOfUnchangedInputsCommitsIdenticalBytes() throws IOException {
        writeWorkspace("1.0.0");
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String before = Files.readString(first.lockfilePath());

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals(before, Files.readString(first.lockfilePath()));
    }

    @Test
    void recordedFingerprintChangesWhenTheLockedPackagesAreEdited() throws IOException {
        writeWorkspace("1.0.0");
        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String committed = Files.readString(result.lockfilePath());

        assertNotEquals(
                reader.read(committed).workspaceResolutionInputFingerprint(),
                WorkspaceResolutionInputFingerprint.fingerprint(
                        new ManifestWorkspaceLoader().load(tempDir),
                        committed.replace("1.0.0\"", "1.0.1\"")));
    }

    @Test
    void recordedFingerprintChangesWhenADependencyChanges() throws IOException {
        writeWorkspace("1.0.0");
        service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        Optional<String> before = recorded();

        writeWorkspace("1.0.1");
        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        assertNotEquals(before, recorded());
    }

    @Test
    void lockedResolveAcceptsALockWrittenBeforeTheFingerprintExisted() throws IOException {
        writeWorkspace("1.0.0");
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String legacy = Files.readString(first.lockfilePath()).lines()
                .filter(line -> !line.startsWith("workspaceResolutionInputFingerprint = "))
                .reduce("", (left, right) -> left + right + "\n");
        Files.writeString(first.lockfilePath(), legacy);

        ResolveResult locked = service.resolve(tempDir, tempDir.resolve("cache"), true, false);

        assertEquals(first.resolvedCount(), locked.resolvedCount());
        assertFalse(Files.readString(first.lockfilePath())
                .contains("workspaceResolutionInputFingerprint"));
    }

    @Test
    void ordinaryResolveWritesTheFingerprintOntoALegacyLock() throws IOException {
        writeWorkspace("1.0.0");
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        Files.writeString(first.lockfilePath(), Files.readString(first.lockfilePath()).lines()
                .filter(line -> !line.startsWith("workspaceResolutionInputFingerprint = "))
                .reduce("", (left, right) -> left + right + "\n"));

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        assertTrue(recorded().isPresent());
    }

    private Optional<String> recorded() throws IOException {
        return reader.read(Files.readString(tempDir.resolve("zolt.lock")))
                .workspaceResolutionInputFingerprint();
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
        byte[] body = responses.get(exchange.getRequestURI().getPath());
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
