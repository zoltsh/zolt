package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.cli.command.CommandServiceBundles.CommandCoverageServices;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.quarkus.QuarkusDependencyRequestPlanner;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceCoverageCommandResolverTest {
    private final Map<String, byte[]> responses = new HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void startServer() {
        try {
            server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0);
        } catch (IOException exception) {
            assumeTrue(
                    false,
                    "local HTTP server sockets are unavailable: "
                            + exception.getMessage());
            return;
        }
        server.createContext("/", this::handle);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void workspaceCoverageUsesCommandTransportAndQuarkusPlanner()
            throws Exception {
        addArtifact(
                "io.quarkus",
                "quarkus-rest",
                "3.33.0",
                Map.of(
                        "META-INF/quarkus-extension.properties",
                        "deployment-artifact=io.quarkus:quarkus-rest-deployment:3.33.0\n"));
        addArtifact(
                "io.quarkus",
                "quarkus-rest-deployment",
                "3.33.0",
                Map.of());
        addArtifact(
                "org.junit.jupiter",
                "junit-jupiter",
                "5.11.4",
                Map.of());
        addArtifact(
                "org.junit.platform",
                "junit-platform-console",
                "1.11.4",
                Map.of());
        addArtifact(
                "org.jacoco",
                "org.jacoco.agent",
                "0.8.14",
                Map.of());
        addClassifierJar(
                "org.jacoco",
                "org.jacoco.agent",
                "0.8.14",
                "runtime");
        addArtifact(
                "org.jacoco",
                "org.jacoco.cli",
                "0.8.14",
                Map.of());

        Path commandConfig = tempDir.resolve("command-config.toml");
        Files.writeString(commandConfig, "version = 1\n");
        MavenRepositoryClient repositoryClient =
                new MavenRepositoryClient(
                        CommandNetwork.transport(commandConfig));
        ResolveService resolveService = new ResolveService(
                new QuarkusDependencyRequestPlanner(),
                repositoryClient);
        CommandCoverageServices services =
                CommandFrameworkServices.coverageCommandServices(
                        resolveService);
        assertSame(resolveService, services.resolveService());

        Path root = tempDir.resolve("workspace");
        Path member = root.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                include = ["apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(repositoryUri()));
        Files.writeString(member.resolve("zolt.toml"), memberConfig());

        new WorkspaceResolveService(services.resolveService())
                .resolveWithCoverageTooling(
                        root,
                        tempDir.resolve("cache"));

        var lockfile = new ZoltLockfileReader().read(
                root.resolve("zolt.lock"));
        assertTrue(lockfile.packages().stream().anyMatch(pkg ->
                pkg.packageId().toString().equals(
                        "io.quarkus:quarkus-rest-deployment")
                        && pkg.scope()
                                == DependencyScope.QUARKUS_DEPLOYMENT));
        assertTrue(lockfile.packages().stream().anyMatch(pkg ->
                pkg.packageId().toString().equals(
                        "org.jacoco:org.jacoco.agent")
                        && pkg.scope() == DependencyScope.TOOL_COVERAGE));
        assertTrue(lockfile.packages().stream().anyMatch(pkg ->
                pkg.packageId().toString().equals(
                        "org.jacoco:org.jacoco.cli")
                        && pkg.scope() == DependencyScope.TOOL_COVERAGE));
        assertTrue(requests.get() > 0);
    }

    private String memberConfig() {
        return """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [package]
                mode = "quarkus"

                [dependencies]
                "io.quarkus:quarkus-rest" = "3.33.0"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.11.4"
                """;
    }

    private URI repositoryUri() {
        return URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/maven2/");
    }

    private void addArtifact(
            String group,
            String artifact,
            String version,
            Map<String, String> jarEntries) {
        String base = base(group, artifact, version);
        responses.put(
                base + ".pom",
                simplePom(group, artifact, version).getBytes(
                        StandardCharsets.UTF_8));
        responses.put(base + ".jar", jarBytes(jarEntries));
    }

    private void addClassifierJar(
            String group,
            String artifact,
            String version,
            String classifier) {
        responses.put(
                base(group, artifact, version)
                        + "-"
                        + classifier
                        + ".jar",
                jarBytes(Map.of()));
    }

    private static String base(
            String group,
            String artifact,
            String version) {
        return "/maven2/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version;
    }

    private static String simplePom(
            String group,
            String artifact,
            String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }

    private static byte[] jarBytes(Map<String, String> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(bytes)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    jar.putNextEntry(new JarEntry(entry.getKey()));
                    jar.write(entry.getValue().getBytes(
                            StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        byte[] body = responses.get(exchange.getRequestURI().getPath());
        try (exchange) {
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
