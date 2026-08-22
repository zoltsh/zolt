package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceResolveServiceExportedApiTest {
    private final WorkspaceResolveService service = new WorkspaceResolveService();
    private final ZoltLockfileReader lockfileReader = new ZoltLockfileReader();
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
        addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "lib", "1.0.0", pom("com.example", "lib", "1.0.0"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void recordsExportedWorkspacePackageOwners() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", """

                [dependencies.api]
                "com.acme:core" = { workspace = true }
                """);
        member("modules/core", "core", "");

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        LockPackage core = lockPackage(result, "com.acme", "core");
        assertEquals(List.of("apps/api"), core.members());
        assertEquals(List.of("apps/api"), core.exportedBy());
    }

    @Test
    void recordsExportedExternalApiDependencies() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies.api]
                "com.example:app" = "1.0.0"
                """);

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        LockPackage app = lockPackage(result, "com.example", "app");
        LockPackage lib = lockPackage(result, "com.example", "lib");
        assertEquals(List.of("apps/api"), app.members());
        assertEquals(List.of("apps/api"), app.exportedBy());
        assertEquals(List.of("apps/api"), lib.members());
        assertEquals(List.of(), lib.exportedBy());
    }

    @Test
    void recordsExportedManagedApiDependencies() throws IOException {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [platforms]
                "com.example:platform" = "1.0.0"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies.api]
                "com.example:app" = { managed = true }
                """);

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        LockPackage app = lockPackage(result, "com.example", "app");
        assertEquals("1.0.0", app.version());
        assertEquals(List.of("apps/api"), app.exportedBy());
    }

    private LockPackage lockPackage(ResolveResult result, String groupId, String artifactId) throws IOException {
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(groupId, artifactId)))
                .findFirst()
                .orElseThrow();
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = 21
                %s""".formatted(name, extraToml));
    }

    private void addArtifact(String groupId, String artifactId, String version, String pom) {
        String base = artifactBase(groupId, artifactId, version);
        responses.put(base + ".pom", pom.getBytes(StandardCharsets.UTF_8));
        responses.put(base + ".jar", new byte[] {0x50, 0x4b, 0x03, 0x04});
    }

    private void addPom(String groupId, String artifactId, String version, String pom) {
        responses.put(artifactBase(groupId, artifactId, version) + ".pom", pom.getBytes(StandardCharsets.UTF_8));
    }

    private static String artifactBase(String groupId, String artifactId, String version) {
        return "/maven2/%s/%s/%s/%s-%s".formatted(groupId.replace('.', '/'), artifactId, version, artifactId, version);
    }

    private static String pom(String groupId, String artifactId, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = responses.get(exchange.getRequestURI().getPath());
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
