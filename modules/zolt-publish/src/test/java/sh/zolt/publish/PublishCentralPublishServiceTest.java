package sh.zolt.publish;

import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.net.NetworkTransport;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end: assemble a signed bundle from config, upload to a fixture Portal, and — only under --wait — poll status. */
final class PublishCentralPublishServiceTest {
    private static final String PASSPHRASE = "zolt-service-passphrase";

    @TempDir
    private Path tempDir;

    @Test
    void uploadWithoutWaitReturnsUploadedAndMakesNoStatusRequest() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path gnupgHome = isolatedGnupgHome();
        assumeTrue(generateSigningKey(gnupgHome), "gpg could not generate a throwaway signing key");

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
            return;
        }
        AtomicReference<String> uploadAuth = new AtomicReference<>();
        AtomicReference<byte[]> uploadBody = new AtomicReference<>();
        AtomicInteger statusRequests = new AtomicInteger();
        server.createContext("/api/v1/publisher/upload", exchange -> {
            uploadAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uploadBody.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 201, "deployment-42");
        });
        // Central accepted the bundle but the status endpoint is broken. Without --wait it must never be hit,
        // so a transient 500 can never turn a live deployment into a failure that provokes a duplicate retry.
        server.createContext("/api/v1/publisher/status", exchange -> {
            statusRequests.incrementAndGet();
            respond(exchange, 500, "{\"error\":\"transient\"}");
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            Path root = writeProject(baseUrl);
            Function<String, String> environment = Map.of(
                    "ZOLT_CENTRAL_TOKEN", "dG9rZW4=",
                    "ZOLT_SIGNING_PASS", PASSPHRASE,
                    "GNUPGHOME", gnupgHome.toString())::get;
            PublishDryRunPlan plan = new PublishDryRunService(environment).plan(root, false);
            PublishCentralPublishService service = new PublishCentralPublishService(
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new CentralPortalClient(NetworkTransport.direct()),
                    environment);

            PublishCentralUploadResult result = service.publish(root, plan);

            assertEquals("deployment-42", result.deploymentId());
            assertEquals(CentralPublishingType.AUTOMATIC, result.publishingType());
            assertEquals(PublishCentralPublishOutcome.UPLOADED, result.outcome());
            assertTrue(result.status().isEmpty(), "a no-wait upload reports no polled status");
            assertEquals(0, statusRequests.get(), "a no-wait upload must make zero status requests");
            assertEquals("Bearer dG9rZW4=", uploadAuth.get());
            // The uploaded multipart body carries the signed bundle zip.
            assertTrue(new String(uploadBody.get(), StandardCharsets.UTF_8).contains("name=\"bundle\""));
            assertTrue(result.bundle().entries().stream().anyMatch(entry -> entry.endsWith(".asc")),
                    result.bundle().entries().toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void waitPollsDeploymentToPublishedTerminalStateAndReportsOutcome() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path gnupgHome = isolatedGnupgHome();
        assumeTrue(generateSigningKey(gnupgHome), "gpg could not generate a throwaway signing key");

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
            return;
        }
        server.createContext("/api/v1/publisher/upload", exchange -> respond(exchange, 201, "deployment-99"));
        // Terminal on the first poll, so the wait returns immediately without sleeping between polls.
        server.createContext("/api/v1/publisher/status", exchange ->
                respond(exchange, 200, "{\"deploymentId\":\"deployment-99\",\"deploymentState\":\"PUBLISHED\"}"));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            Path root = writeProject(baseUrl);
            Function<String, String> environment = Map.of(
                    "ZOLT_CENTRAL_TOKEN", "dG9rZW4=",
                    "ZOLT_SIGNING_PASS", PASSPHRASE,
                    "GNUPGHOME", gnupgHome.toString())::get;
            PublishDryRunPlan plan = new PublishDryRunService(environment).plan(root, false);
            PublishCentralPublishService service = new PublishCentralPublishService(
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new CentralPortalClient(NetworkTransport.direct()),
                    environment);

            PublishCentralUploadResult result = service.publish(root, plan, Optional.of(Duration.ofSeconds(300)));

            assertEquals("deployment-99", result.deploymentId());
            assertEquals("PUBLISHED", result.status().orElseThrow().state());
            assertEquals(PublishCentralPublishOutcome.PUBLISHED, result.outcome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void waitSurfacesAStatusFailureThroughTheWaiter() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path gnupgHome = isolatedGnupgHome();
        assumeTrue(generateSigningKey(gnupgHome), "gpg could not generate a throwaway signing key");

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
            return;
        }
        server.createContext("/api/v1/publisher/upload", exchange -> respond(exchange, 201, "deployment-500"));
        server.createContext("/api/v1/publisher/status", exchange -> respond(exchange, 500, "upstream is down"));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            Path root = writeProject(baseUrl);
            Function<String, String> environment = Map.of(
                    "ZOLT_CENTRAL_TOKEN", "dG9rZW4=",
                    "ZOLT_SIGNING_PASS", PASSPHRASE,
                    "GNUPGHOME", gnupgHome.toString())::get;
            PublishDryRunPlan plan = new PublishDryRunService(environment).plan(root, false);
            PublishCentralPublishService service = new PublishCentralPublishService(
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new CentralPortalClient(NetworkTransport.direct()),
                    environment);

            // Under --wait the status failure is surfaced through the waiter's error path, not swallowed.
            PublishException failure = assertThrows(PublishException.class,
                    () -> service.publish(root, plan, Optional.of(Duration.ofSeconds(300))));
            assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private Path writeProject(String baseUrl) throws IOException {
        Path root = tempDir.resolve("central-lib");
        Files.createDirectories(root.resolve("target"));
        Path artifact = root.resolve("target/central-lib-0.1.0.jar");
        Files.writeString(artifact, "central package\n");
        Files.writeString(root.resolve("target/central-lib-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/central-lib-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(prefixedSha256(artifact)));
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(root.resolve("zolt.toml"), """
                [project]
                name = "central-lib"
                version = "0.1.0"
                group = "com.example"
                java = "%d"

                [publish.central]
                tokenEnv = "ZOLT_CENTRAL_TOKEN"
                publishingType = "automatic"
                baseUrl = "%s"

                [publish.signing]
                enabled = true
                passphraseEnv = "ZOLT_SIGNING_PASS"
                """.formatted(Runtime.version().feature(), baseUrl));
        return root;
    }

    private Path isolatedGnupgHome() throws IOException {
        Path gnupgHome = tempDir.resolve("gnupg");
        Files.createDirectories(gnupgHome);
        try {
            Files.setPosixFilePermissions(gnupgHome, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem.
        }
        return gnupgHome;
    }

    private boolean generateSigningKey(Path gnupgHome) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("gpg"));
        command.addAll(List.of(
                "--batch", "--pinentry-mode", "loopback", "--passphrase", PASSPHRASE,
                "--quick-generate-key", "Zolt Service Test <service@zolt.test>", "default", "sign", "0"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GNUPGHOME", gnupgHome.toString());
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private static boolean gpgAvailable() {
        try {
            Process process = new ProcessBuilder("gpg", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String prefixedSha256(Path path) throws IOException {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
