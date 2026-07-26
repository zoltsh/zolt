package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.resolve.ResolveResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceResolveServiceLockedTest {
    private final WorkspaceResolveService service = new WorkspaceResolveService();
    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, String> authorizationByPath = new ConcurrentHashMap<>();

    @TempDir
    private Path tempDir;

    private HttpServer server;
    private URI baseUri;
    private String requiredAuthorization;

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
    void lockedWorkspaceResolveSucceedsWhenRootLockfileMatches() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String existing = Files.readString(first.lockfilePath());

        ResolveResult locked = service.resolve(tempDir, tempDir.resolve("cache"), true, false);

        assertEquals(first.resolvedCount(), locked.resolvedCount());
        assertEquals(existing, Files.readString(first.lockfilePath()));
    }

    @Test
    void workspaceResolveAndLockedResolveAuthenticateMemberRepositoryRequests() throws IOException {
        String token = System.getenv("PATH");
        assumeTrue(token != null && !token.isBlank(), "PATH is required as a non-secret test token");
        requiredAuthorization = "Bearer " + token;
        workspace("""
                [workspace]
                name = "authenticated-platform"
                members = ["apps/api"]

                [repositories]
                internal = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [repositories]
                internal = { url = "%s", credentials = "company" }

                [repositoryCredentials.company]
                tokenEnv = "PATH"

                [dependencies]
                "com.example:app" = "1.0.0"
                """.formatted(baseUri));

        service.resolve(tempDir, tempDir.resolve("first-cache"), false, false);
        assertTrue(authorizationByPath.values().stream().allMatch(requiredAuthorization::equals));

        authorizationByPath.clear();
        service.resolve(tempDir, tempDir.resolve("locked-cache"), true, false);
        assertTrue(!authorizationByPath.isEmpty());
        assertTrue(authorizationByPath.values().stream().allMatch(requiredAuthorization::equals));
    }

    @Test
    void lockedWorkspaceResolveIgnoresJavaToolchainMetadata() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String existing = withJavaToolchainBlock(Files.readString(first.lockfilePath()));
        Files.writeString(first.lockfilePath(), existing);

        ResolveResult locked = service.resolve(tempDir, tempDir.resolve("cache"), true, false);

        assertEquals(first.resolvedCount(), locked.resolvedCount());
        assertEquals(existing, Files.readString(first.lockfilePath()));
    }

    @Test
    void unlockedWorkspaceResolvePreservesJavaToolchainMetadata() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        Files.writeString(first.lockfilePath(), withJavaToolchainBlock(Files.readString(first.lockfilePath())));

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        String content = Files.readString(first.lockfilePath());
        assertTrue(content.contains("[[toolchain.java]]"));
        assertTrue(content.contains("layout.executables.nativeImage = \"lib/svm/bin/native-image\""));
    }

    @Test
    void lockedWorkspaceResolveCarriesForwardExistingCoverageTooling() throws IOException {
        addArtifact(
                "org.junit.platform",
                "junit-platform-console-standalone",
                "1.11.4",
                pom("org.junit.platform", "junit-platform-console-standalone", "1.11.4"));
        addPom("org.jacoco", "org.jacoco.agent", "0.8.14", pom("org.jacoco", "org.jacoco.agent", "0.8.14"));
        addArtifactWithClassifier("org.jacoco", "org.jacoco.agent", "0.8.14", "runtime");
        addArtifact("org.jacoco", "org.jacoco.cli", "0.8.14", pom("org.jacoco", "org.jacoco.cli", "0.8.14"));
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"

                [test.dependencies]
                "org.junit.platform:junit-platform-console-standalone" = "1.11.4"
                """);
        ResolveResult coverage = service.resolveWithCoverageTooling(tempDir, tempDir.resolve("cache"));
        String existing = Files.readString(coverage.lockfilePath());

        ResolveResult locked = service.resolve(tempDir, tempDir.resolve("cache"), true, false);

        assertTrue(existing.contains("scope = \"tool-coverage\""));
        assertEquals(coverage.resolvedCount(), locked.resolvedCount());
        assertEquals(existing, Files.readString(coverage.lockfilePath()));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "21"
                %s""".formatted(name, extraToml));
    }

    private void addArtifact(String groupId, String artifactId, String version, String pom) {
        String base = "/maven2/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        responses.put(base + ".pom", pom.getBytes(StandardCharsets.UTF_8));
        responses.put(base + ".jar", new byte[] {0x50, 0x4b, 0x03, 0x04});
    }

    private void addArtifactWithClassifier(String groupId, String artifactId, String version, String classifier) {
        String base = "/maven2/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        responses.put(base + "-" + classifier + ".jar", new byte[] {0x50, 0x4b, 0x03, 0x04});
    }

    private void addPom(String groupId, String artifactId, String version, String pom) {
        String base = "/maven2/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        responses.put(base + ".pom", pom.getBytes(StandardCharsets.UTF_8));
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
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizationByPath.put(
                exchange.getRequestURI().getPath(),
                authorization == null ? "<none>" : authorization);
        if (requiredAuthorization != null && !requiredAuthorization.equals(authorization)) {
            respond(exchange, 401, "authentication required".getBytes(StandardCharsets.UTF_8));
            return;
        }
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

    private static String withJavaToolchainBlock(String content) {
        return content.stripTrailing() + """

                [[toolchain.java]]
                id = "java-graalvm-community-21-native-image"
                request.version = "21"
                request.distribution = "graalvm-community"
                request.features = ["native-image"]
                request.policy = "prefer-managed"
                platform.os = "macos"
                platform.arch = "aarch64"
                resolved.version = "21"
                resolved.distribution = "graalvm-community"
                artifact.catalog = "builtin:java-graalvm-community-21-native-image"
                layout.javaHome = "Contents/Home"
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                layout.executables.nativeImage = "lib/svm/bin/native-image"
                """;
    }
}
