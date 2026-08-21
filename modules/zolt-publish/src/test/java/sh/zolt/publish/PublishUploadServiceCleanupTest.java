package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.toml.ZoltTomlParser;

final class PublishUploadServiceCleanupTest {
    @TempDir
    private Path tempDir;

    @Test
    void cleanupFailureAfterAllPutsStillReportsSuccessfulPublication()
            throws Exception {
        Path projectDir = tempDir.resolve("cleanup-warning-lib");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve(
                "target/cleanup-warning-lib-0.1.0.jar");
        Files.writeString(artifact, "published package\n");
        Files.writeString(
                projectDir.resolve(
                        "target/cleanup-warning-lib-0.1.0.jar.zolt-package.json"),
                """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/cleanup-warning-lib-0.1.0.jar",
                  "archiveSha256": "%s"
                }
                """.formatted(prefixedSha256(artifact)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        try (var recorder =
                PublishUploadServiceSigningTest.Recorder.start()) {
            Files.writeString(projectDir.resolve("zolt.toml"), """
                    [project]
                    name = "cleanup-warning-lib"
                    version = "0.1.0"
                    group = "com.example"
                    java = "%d"

                    [publish]
                    releaseRepository = "local"

                    [publish.repositories.local]
                    url = "%s"
                    """.formatted(
                    Runtime.version().feature(),
                    recorder.baseUri()));
            PublishTestPackageEvidence.write(projectDir);
            Function<String, String> environment = key -> null;
            PublishUploadService service = new PublishUploadService(
                    new PublishDryRunService(environment),
                    new ZoltTomlParser(),
                    new PublishSettingsReader(),
                    new MavenRepositoryClient(),
                    environment,
                    manifest -> java.util.Optional.of(
                            "injected cleanup failure at " + manifest));

            PublishUploadResult result = service.upload(projectDir);

            assertTrue(result.cleanupWarning().isPresent());
            assertTrue(PublishUploadFormatter.text(result)
                    .contains("Status: uploaded"));
            assertTrue(PublishUploadFormatter.text(result)
                    .contains("Warning: injected cleanup failure"));
            assertFalse(recorder.paths().isEmpty());
            Path manifest =
                    PublicationTransactionManifest.transactionPath(
                            projectDir.resolve(
                                    "target/publish/publish-staging"),
                            recorder.baseUri().normalize().toString(),
                            "com.example:cleanup-warning-lib:0.1.0");
            PublicationTransactionManifest retained =
                    PublicationTransactionManifest.read(manifest)
                            .orElseThrow();
            assertFalse(retained.resume().recordedHashes().isEmpty());
        }
    }

    private static String prefixedSha256(Path path) throws IOException {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
