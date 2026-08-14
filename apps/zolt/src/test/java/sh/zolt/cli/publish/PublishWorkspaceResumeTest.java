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
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P1 failure-injection regression for the plain-repository publish resume. Publishes the
 * {@code platform-family} to an IMMUTABLE fixture repository (it rejects any re-PUT of a stored path,
 * 409) that also rejects {@code acme-http}'s jar the first time (403). The emitted resume command must
 * then re-run EXACTLY the remaining members — never dependency-expanding back to the already-uploaded
 * {@code acme-core}, whose re-PUT the immutable repo would reject — while preserving {@code --sbom}
 * and {@code --allow-mixed-versions}.
 */
final class PublishWorkspaceResumeTest {
    private static final String CORE_JAR =
            "/maven2/com/acme/acme-core/1.0.0/acme-core-1.0.0.jar";

    @Test
    void matchingLargeWorkspaceArtifactResumesAndDifferentContentConflicts(@TempDir Path tempDir)
            throws IOException {
        ImmutableRepository repository = ImmutableRepository.start();
        try {
            Path family = tempDir.resolve("large-platform-family");
            copyTree(exampleRoot().resolve("platform-family"), family);
            for (String member : new String[] {"acme-core", "acme-http", "acme-bom"}) {
                rewrite(family.resolve(member).resolve("zolt.toml"),
                        "https://repo.example.test/releases", repository.baseUri());
            }
            Path resource = family.resolve("acme-core/src/main/resources/large.bin");
            Files.createDirectories(resource.getParent());
            byte[] bytes = new byte[9 * 1024 * 1024];
            new Random(42L).nextBytes(bytes);
            Files.write(resource, bytes);
            Path cache = tempDir.resolve("cache");
            run(family, cache, "resolve", "--workspace");
            run(family, cache, "build", "--workspace");
            run(family, cache, "package", "--workspace");
            assertTrue(Files.size(family.resolve("acme-core/target/acme-core-1.0.0.jar")) > 8L * 1024L * 1024L);

            CliTestSupport.CommandResult first = executeIn(family, cache, "publish", "--workspace");
            CliTestSupport.CommandResult matchingResume = executeIn(family, cache, "publish", "--workspace");

            assertEquals(0, first.exitCode(), first.stdout() + first.stderr());
            assertEquals(0, matchingResume.exitCode(), matchingResume.stdout() + matchingResume.stderr());
            assertEquals(1, repository.putCount(CORE_JAR), "matching large workspace JAR must not be re-PUT");

            repository.changeOneByte(CORE_JAR);
            CliTestSupport.CommandResult conflict = executeIn(family, cache, "publish", "--workspace");

            assertEquals(1, conflict.exitCode(), conflict.stdout() + conflict.stderr());
            assertTrue(conflict.stdout().contains("already holds different content"), conflict.stdout());
            assertEquals(1, repository.putCount(CORE_JAR), "conflicting large workspace JAR must not be overwritten");
        } finally {
            repository.close();
        }
    }

    @Test
    void resumeUploadsRemainingMembersExactlyWithoutRePuttingTheProvider(@TempDir Path tempDir) throws IOException {
        ImmutableRepository repository = ImmutableRepository.start();
        try {
            Path family = tempDir.resolve("platform-family");
            copyTree(exampleRoot().resolve("platform-family"), family);
            for (String member : new String[] {"acme-core", "acme-http", "acme-bom"}) {
                rewrite(family.resolve(member).resolve("zolt.toml"),
                        "https://repo.example.test/releases", repository.baseUri());
            }
            Path cache = tempDir.resolve("cache");
            run(family, cache, "resolve", "--workspace");
            run(family, cache, "build", "--workspace");
            run(family, cache, "package", "--workspace");

            // First publish: the repo rejects acme-http's jar, so publishing fails after acme-core lands.
            repository.failPutPathSuffix = "/acme-http/1.0.0/acme-http-1.0.0.jar";
            CliTestSupport.CommandResult first =
                    executeIn(family, cache, "publish", "--workspace", "--sbom", "--allow-mixed-versions");
            assertEquals(1, first.exitCode(), first.stdout() + first.stderr());
            assertTrue(repository.has("/maven2/com/acme/acme-core/1.0.0/acme-core-1.0.0.jar"), "provider uploaded");
            assertFalse(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar"), "consumer rejected");

            String resumeCommand = first.stdout().lines()
                    .filter(line -> line.startsWith("Resume with: "))
                    .map(line -> line.substring("Resume with: ".length()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no resume command in:\n" + first.stdout()));
            // Exact members (failed + remaining, never acme-core) with the original options preserved.
            assertEquals(
                    "zolt publish --workspace --resume-members acme-http,acme-bom --allow-mixed-versions --sbom",
                    resumeCommand);

            // Execute the EMITTED command verbatim against the same immutable repo.
            repository.failPutPathSuffix = null;
            String[] resumeArgs = resumeCommand.substring("zolt ".length()).split(" ");
            CliTestSupport.CommandResult resume = executeIn(family, cache, resumeArgs);

            assertEquals(0, resume.exitCode(), resume.stdout() + resume.stderr());
            // acme-core was NOT re-PUT (its jar was uploaded exactly once, in the first publish).
            assertEquals(1, repository.putCount("/maven2/com/acme/acme-core/1.0.0/acme-core-1.0.0.jar"),
                    "provider must never be re-PUT on resume");
            // The remaining members uploaded, and the SBOM rode along (options preserved).
            assertTrue(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar"), "consumer jar uploaded");
            assertTrue(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.pom"), "consumer pom uploaded");
            assertTrue(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0-cyclonedx.json"),
                    "consumer SBOM uploaded on resume");
            assertTrue(repository.has("/maven2/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0.pom"), "bom uploaded");
            // The absent already-published provider is a note, not a blocker.
            assertTrue(resume.stdout().contains("already published"), resume.stdout());
        } finally {
            repository.close();
        }
    }

    @Test
    void resumeAfterAChecksumFailureUploadsOnlyTheMissingFilesBackedByDurableState(@TempDir Path tempDir)
            throws IOException {
        // A mid-member failure: acme-http's jar and its md5 land, then its sha1 checksum PUT is rejected.
        // Resume must upload only the missing sha1 (and remaining members) and re-PUT nothing that landed.
        midMemberFailureResumesUploadingOnlyTheMissingFile(
                tempDir,
                "/acme-http/1.0.0/acme-http-1.0.0.jar.sha1",
                "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar.sha1",
                new String[] {
                    "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar",
                    "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar.md5"
                },
                false);
    }

    @Test
    void resumeAfterASupplementalFailureUploadsOnlyTheMissingFilesBackedByDurableState(@TempDir Path tempDir)
            throws IOException {
        // With --sbom, acme-http's CycloneDX supplemental PUT is rejected after the jar + checksums land.
        midMemberFailureResumesUploadingOnlyTheMissingFile(
                tempDir,
                "/acme-http/1.0.0/acme-http-1.0.0-cyclonedx.json",
                "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0-cyclonedx.json",
                new String[] {
                    "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar",
                    "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar.sha256"
                },
                true);
    }

    @Test
    void resumeAfterAPomFailureUploadsOnlyTheMissingFilesBackedByDurableState(@TempDir Path tempDir)
            throws IOException {
        // The final POM PUT is rejected after everything else in acme-http landed.
        midMemberFailureResumesUploadingOnlyTheMissingFile(
                tempDir,
                "/acme-http/1.0.0/acme-http-1.0.0.pom",
                "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.pom",
                new String[] {
                    "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar",
                    "/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar.sha1"
                },
                false);
    }

    private void midMemberFailureResumesUploadingOnlyTheMissingFile(
            Path tempDir, String failSuffix, String failedPath, String[] landedPaths, boolean sbom) throws IOException {
        ImmutableRepository repository = ImmutableRepository.start();
        try {
            Path family = prepareFamily(tempDir, repository);
            Path cache = tempDir.resolve("cache");
            Path statePath = family.resolve("target/zolt-publish/resume-state");
            String coreJar = "/maven2/com/acme/acme-core/1.0.0/acme-core-1.0.0.jar";

            repository.failPutPathSuffix = failSuffix;
            CliTestSupport.CommandResult first = sbom
                    ? executeIn(family, cache, "publish", "--workspace", "--sbom")
                    : executeIn(family, cache, "publish", "--workspace");
            assertEquals(1, first.exitCode(), first.stdout() + first.stderr());
            assertTrue(Files.exists(statePath), "durable resume state is written on failure");
            for (String landed : landedPaths) {
                assertTrue(repository.has(landed), landed + " landed before the failure");
            }
            assertFalse(repository.has(failedPath), failedPath + " did not land");

            String resumeCommand = resumeCommand(first);
            repository.failPutPathSuffix = null;
            CliTestSupport.CommandResult resume = executeIn(family, cache, resumeArgs(resumeCommand));

            assertEquals(0, resume.exitCode(), resume.stdout() + resume.stderr());
            // Already-landed files are skipped from durable state: each was PUT exactly once, never re-PUT.
            for (String landed : landedPaths) {
                assertEquals(1, repository.putCount(landed), landed + " must not be re-PUT on resume");
            }
            assertEquals(1, repository.putCount(coreJar), "the fully-published provider is never re-PUT");
            assertTrue(repository.has(failedPath), "the previously-failed file uploaded on resume");
            assertTrue(repository.has("/maven2/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0.pom"), "bom uploaded");
            assertFalse(Files.exists(statePath), "resume state is cleared after a successful publish");
            // The absent provider is confirmed published by the recorded state, not blindly assumed.
            assertTrue(resume.stdout().contains("already published"), resume.stdout());
        } finally {
            repository.close();
        }
    }

    @Test
    void aManualResumeWithoutMatchingStateRefusesActionably(@TempDir Path tempDir) throws IOException {
        ImmutableRepository repository = ImmutableRepository.start();
        try {
            Path family = prepareFamily(tempDir, repository);
            Path cache = tempDir.resolve("cache");
            // No prior interrupted publish → no resume state; the hidden flag must not publish blindly.
            CliTestSupport.CommandResult result =
                    executeIn(family, cache, "publish", "--workspace", "--resume-members", "acme-http");

            assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("no publish resume state"), result.stdout());
            assertFalse(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar"), "nothing uploaded");
        } finally {
            repository.close();
        }
    }

    @Test
    void aLegacyV1ResumeStateIsRefusedRatherThanGuessedAt(@TempDir Path tempDir) throws IOException {
        ImmutableRepository repository = ImmutableRepository.start();
        try {
            Path family = prepareFamily(tempDir, repository);
            Path cache = tempDir.resolve("cache");
            Path statePath = family.resolve("target/zolt-publish/resume-state");
            // A resume-state file written by an older Zolt (schema v1) predates the transaction manifest and
            // cannot be trusted to resume; the hidden flag must refuse outright rather than guess.
            Files.createDirectories(statePath.getParent());
            Files.writeString(statePath, "schema=zolt.publish-resume.v1\nplanHash=deadbeef\n"
                    + "completed=com/acme/acme-http/1.0.0/acme-http-1.0.0.jar\n");

            CliTestSupport.CommandResult result =
                    executeIn(family, cache, "publish", "--workspace", "--resume-members", "acme-http,acme-bom");

            assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("older schema"), result.stdout());
            assertFalse(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar"), "nothing uploaded");
        } finally {
            repository.close();
        }
    }

    @Test
    void aResumeAgainstAChangedPlanRefuses(@TempDir Path tempDir) throws IOException {
        ImmutableRepository repository = ImmutableRepository.start();
        try {
            Path family = prepareFamily(tempDir, repository);
            Path cache = tempDir.resolve("cache");
            Path statePath = family.resolve("target/zolt-publish/resume-state");
            repository.failPutPathSuffix = "/acme-http/1.0.0/acme-http-1.0.0.jar";
            CliTestSupport.CommandResult first = executeIn(family, cache, "publish", "--workspace");
            assertEquals(1, first.exitCode(), first.stdout() + first.stderr());
            assertTrue(Files.exists(statePath));

            // The recorded plan no longer matches what would upload: a stale resume must refuse.
            corruptPlanHash(statePath);
            repository.failPutPathSuffix = null;
            CliTestSupport.CommandResult resume =
                    executeIn(family, cache, "publish", "--workspace", "--resume-members", "acme-http,acme-bom");

            assertEquals(1, resume.exitCode(), resume.stdout() + resume.stderr());
            assertTrue(resume.stdout().contains("plan changed"), resume.stdout());
            assertFalse(repository.has("/maven2/com/acme/acme-http/1.0.0/acme-http-1.0.0.jar"), "nothing uploaded");
        } finally {
            repository.close();
        }
    }

    private static Path prepareFamily(Path tempDir, ImmutableRepository repository) throws IOException {
        Path family = tempDir.resolve("platform-family");
        copyTree(exampleRoot().resolve("platform-family"), family);
        for (String member : new String[] {"acme-core", "acme-http", "acme-bom"}) {
            rewrite(family.resolve(member).resolve("zolt.toml"),
                    "https://repo.example.test/releases", repository.baseUri());
        }
        Path cache = tempDir.resolve("cache");
        run(family, cache, "resolve", "--workspace");
        run(family, cache, "build", "--workspace");
        run(family, cache, "package", "--workspace");
        return family;
    }

    private static String resumeCommand(CliTestSupport.CommandResult result) {
        return result.stdout().lines()
                .filter(line -> line.startsWith("Resume with: "))
                .map(line -> line.substring("Resume with: ".length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no resume command in:\n" + result.stdout()));
    }

    private static String[] resumeArgs(String resumeCommand) {
        return resumeCommand.substring("zolt ".length()).split(" ");
    }

    private static void corruptPlanHash(Path statePath) throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>(Files.readAllLines(statePath));
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("planHash=")) {
                lines.set(index, "planHash=0000000000000000000000000000000000000000000000000000000000000000");
            }
        }
        Files.write(statePath, lines);
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

    /** A Maven repository that stores what PUT sends, rejects re-PUT of a stored path (409, immutable). */
    private static final class ImmutableRepository implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();
        private final Map<String, Integer> putCounts = new ConcurrentHashMap<>();
        private final String baseUri;
        volatile String failPutPathSuffix = null;

        private ImmutableRepository(HttpServer server) {
            this.server = server;
            this.baseUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/";
        }

        static ImmutableRepository start() throws IOException {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                assumeTrue(false, "local HTTP server sockets are unavailable: " + exception.getMessage());
                throw exception;
            }
            ImmutableRepository repository = new ImmutableRepository(server);
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

        int putCount(String path) {
            return putCounts.getOrDefault(path, 0);
        }

        void changeOneByte(String path) {
            store.compute(path, (ignored, bytes) -> {
                byte[] changed = bytes.clone();
                changed[0] ^= 1;
                return changed;
            });
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                putCounts.merge(path, 1, Integer::sum);
                String suffix = failPutPathSuffix;
                if (suffix != null && path.endsWith(suffix)) {
                    respond(exchange, 403, new byte[0]);
                    return;
                }
                if (store.containsKey(path)) {
                    respond(exchange, 409, new byte[0]);
                    return;
                }
                store.put(path, body);
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
