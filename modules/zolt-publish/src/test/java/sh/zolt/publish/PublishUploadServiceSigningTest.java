package sh.zolt.publish;

import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves publish signs every uploaded file and uploads the .asc (and its checksums) when enabled. */
final class PublishUploadServiceSigningTest {
    private static final String PASSPHRASE = "zolt-upload-passphrase";

    @TempDir
    private Path tempDir;

    @Test
    void signingUploadsDetachedSignaturesAndTheirChecksumsForEveryFile() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path gnupgHome = isolatedGnupgHome();
        assumeTrue(generateSigningKey(gnupgHome), "gpg could not generate a throwaway signing key");

        Path projectDir = tempDir.resolve("signed-lib");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/signed-lib-0.1.0.jar");
        Files.writeString(artifact, "signed package\n");
        Files.writeString(projectDir.resolve("target/signed-lib-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/signed-lib-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(prefixedSha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        try (Recorder recorder = Recorder.start()) {
            Files.writeString(projectDir.resolve("zolt.toml"), """
                    [project]
                    name = "signed-lib"
                    version = "0.1.0"
                    group = "com.example"
                    java = "%d"

                    [publish]
                    releaseRepository = "local"

                    [publish.repositories.local]
                    url = "%s"

                    [publish.signing]
                    enabled = true
                    passphraseEnv = "ZOLT_SIGNING_PASS"
                    """.formatted(Runtime.version().feature(), recorder.baseUri()));
            PublishTestPackageEvidence.write(projectDir);

            Function<String, String> environment = Map.of(
                    "ZOLT_SIGNING_PASS", PASSPHRASE,
                    "GNUPGHOME", gnupgHome.toString())::get;
            PublishUploadService service = new PublishUploadService(
                    new PublishDryRunService(environment),
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new MavenRepositoryClient(),
                    environment);

            service.upload(projectDir);

            String base = "/com/example/signed-lib/0.1.0/signed-lib-0.1.0";
            // Detached signatures for the artifact and the POM, plus checksums over the signatures.
            assertTrue(recorder.received(base + ".jar.asc"), recorder.paths());
            assertTrue(recorder.received(base + ".jar.asc.sha1"), recorder.paths());
            assertTrue(recorder.received(base + ".jar.asc.md5"), recorder.paths());
            assertTrue(recorder.received(base + ".pom.asc"), recorder.paths());
            assertTrue(recorder.received(base + ".pom.asc.sha256"), recorder.paths());
            assertTrue(
                    new String(recorder.body(base + ".jar.asc"), StandardCharsets.UTF_8)
                            .contains("-----BEGIN PGP SIGNATURE-----"),
                    "uploaded .asc should be an armored signature");
        }
    }

    @Test
    void unusableSigningKeyFailsBeforeAnyPutRequest() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path emptyGnupgHome = isolatedGnupgHome();
        Path projectDir = tempDir.resolve("missing-key-lib");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/missing-key-lib-0.1.0.jar");
        Files.writeString(artifact, "package bytes\n");
        Files.writeString(projectDir.resolve("target/missing-key-lib-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/missing-key-lib-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(prefixedSha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        try (Recorder recorder = Recorder.start()) {
            Files.writeString(projectDir.resolve("zolt.toml"), """
                    [project]
                    name = "missing-key-lib"
                    version = "0.1.0"
                    group = "com.example"
                    java = "%d"

                    [publish]
                    releaseRepository = "local"

                    [publish.repositories.local]
                    url = "%s"

                    [publish.signing]
                    enabled = true
                    keyId = "0000000000000000"
                    """.formatted(Runtime.version().feature(), recorder.baseUri()));
            PublishTestPackageEvidence.write(projectDir);
            Function<String, String> environment =
                    Map.of("GNUPGHOME", emptyGnupgHome.toString())::get;
            PublishUploadService service = new PublishUploadService(
                    new PublishDryRunService(environment),
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new MavenRepositoryClient(),
                    environment);

            PublishException exception =
                    assertThrows(PublishException.class, () -> service.upload(projectDir));

            assertTrue(exception.getMessage().contains("gpg failed to sign"));
            assertTrue(recorder.paths().isEmpty(), "signing preflight must run before any PUT");
        }
    }

    @Test
    void normalRetryResumesTheOriginalSignatureAfterItsChecksumUploadFails() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path gnupgHome = isolatedGnupgHome();
        assumeTrue(generateSigningKey(gnupgHome), "gpg could not generate a throwaway signing key");

        Path projectDir = tempDir.resolve("resumable-signed-lib");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/resumable-signed-lib-0.1.0.jar");
        Files.writeString(artifact, "resumable signed package\n");
        Files.writeString(projectDir.resolve("target/resumable-signed-lib-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/resumable-signed-lib-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(prefixedSha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        try (Recorder recorder = Recorder.start()) {
            Files.writeString(projectDir.resolve("zolt.toml"), """
                    [project]
                    name = "resumable-signed-lib"
                    version = "0.1.0"
                    group = "com.example"
                    java = "%d"

                    [publish]
                    releaseRepository = "local"

                    [publish.repositories.local]
                    url = "%s"

                    [publish.signing]
                    enabled = true
                    passphraseEnv = "ZOLT_SIGNING_PASS"
                    """.formatted(Runtime.version().feature(), recorder.baseUri()));
            PublishTestPackageEvidence.write(projectDir);
            Function<String, String> environment = Map.of(
                    "ZOLT_SIGNING_PASS", PASSPHRASE,
                    "GNUPGHOME", gnupgHome.toString())::get;
            PublishUploadService service = new PublishUploadService(
                    new PublishDryRunService(environment),
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new MavenRepositoryClient(),
                    environment);
            String base = "/com/example/resumable-signed-lib/0.1.0/resumable-signed-lib-0.1.0";
            String signaturePath = base + ".jar.asc";
            String signatureChecksumPath = signaturePath + ".sha256";
            Path transactionPath = PublicationTransactionManifest.transactionPath(
                    projectDir.resolve("target/publish/publish-staging"),
                    recorder.baseUri().normalize().toString(),
                    "com.example:resumable-signed-lib:0.1.0");
            recorder.failPutPathSuffix = signatureChecksumPath;

            assertThrows(PublishException.class, () -> service.upload(projectDir));
            assertTrue(recorder.received(signaturePath), "the original signature landed before the failure");
            assertFalse(recorder.received(signatureChecksumPath), "the injected failure kept its checksum absent");
            assertTrue(
                    Files.exists(transactionPath),
                    "the exact signed transaction remains durable after the failed invocation");
            byte[] originalSignature = recorder.body(signaturePath);
            Map<String, Integer> completedPutCounts = recorder.completedPutCounts();

            recorder.failPutPathSuffix = null;
            service.upload(projectDir);

            assertArrayEquals(originalSignature, recorder.body(signaturePath), "retry retains signature S1");
            assertEquals(1, recorder.putCount(signaturePath), "the completed signature is verified, not re-PUT");
            for (Map.Entry<String, Integer> completed : completedPutCounts.entrySet()) {
                assertEquals(
                        completed.getValue(),
                        recorder.putCount(completed.getKey()),
                        "completed path must not be re-PUT: " + completed.getKey());
            }
            assertEquals(
                    sha256Hex(originalSignature),
                    new String(recorder.body(signatureChecksumPath), StandardCharsets.UTF_8).trim(),
                    "the missing checksum is derived from retained signature S1");
            assertFalse(
                    Files.exists(transactionPath),
                    "the durable transaction is cleared only after the retry completes");
        }
    }

    private Path isolatedGnupgHome() throws IOException {
        Path gnupgHome = tempDir.resolve("gnupg");
        Files.createDirectories(gnupgHome);
        try {
            Files.setPosixFilePermissions(gnupgHome, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem; gpg still runs.
        }
        return gnupgHome;
    }

    private boolean generateSigningKey(Path gnupgHome) throws IOException, InterruptedException {
        return runGpg(gnupgHome, List.of(
                "--batch",
                "--pinentry-mode", "loopback",
                "--passphrase", PASSPHRASE,
                "--quick-generate-key", "Zolt Upload Test <upload@zolt.test>", "default", "sign", "0")) == 0;
    }

    private static int runGpg(Path gnupgHome, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("gpg");
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GNUPGHOME", gnupgHome.toString());
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
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

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static final class Recorder implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> putCounts = new ConcurrentHashMap<>();
        private final URI baseUri;
        volatile String failPutPathSuffix;

        private Recorder(HttpServer server) {
            this.server = server;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        static Recorder start() throws IOException {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
                return null;
            }
            Recorder recorder = new Recorder(server);
            server.createContext("/", recorder::handle);
            server.start();
            return recorder;
        }

        URI baseUri() {
            return baseUri;
        }

        boolean received(String path) {
            return uploads.containsKey(path);
        }

        byte[] body(String path) {
            return uploads.get(path).clone();
        }

        String paths() {
            return String.join("\n", uploads.keySet());
        }

        int putCount(String path) {
            AtomicInteger count = putCounts.get(path);
            return count == null ? 0 : count.get();
        }

        Map<String, Integer> completedPutCounts() {
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (String path : uploads.keySet()) {
                counts.put(path, putCount(path));
            }
            return Map.copyOf(counts);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = uploads.get(path);
                if (body == null) {
                    respond(exchange, 404);
                } else {
                    respond(exchange, 200, body);
                }
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                putCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
                if (failPutPathSuffix != null && path.endsWith(failPutPathSuffix)) {
                    respond(exchange, 500);
                    return;
                }
                uploads.put(exchange.getRequestURI().getPath(), body);
                respond(exchange, 201);
                return;
            }
            respond(exchange, 404);
        }

        private static void respond(HttpExchange exchange, int statusCode) throws IOException {
            respond(exchange, statusCode, "ok".getBytes(StandardCharsets.UTF_8));
        }

        private static void respond(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
            try (exchange) {
                exchange.sendResponseHeaders(statusCode, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
