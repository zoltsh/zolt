package sh.zolt.publish;

import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishUploadServiceTargetIsolationTest {
    private static final String PASSPHRASE = "zolt-upload-passphrase";

    @TempDir
    private Path tempDir;

    @Test
    void repositoryTransactionsKeepIndependentSignatureBytesForTheSameGav() throws Exception {
        assumeTrue(gpgAvailable(), "gpg is not installed");
        Path gnupgHome = isolatedGnupgHome();
        assumeTrue(generateSigningKey(gnupgHome), "gpg could not generate a throwaway signing key");

        Path projectDir = tempDir.resolve("multi-target-signed-lib");
        Files.createDirectories(projectDir.resolve("target"));
        Path artifact = projectDir.resolve("target/multi-target-signed-lib-0.1.0.jar");
        Files.writeString(artifact, "multi-target signed package\n");
        Files.writeString(projectDir.resolve("target/multi-target-signed-lib-0.1.0.jar.zolt-package.json"), """
                {
                  "schema": "zolt.package-evidence.v1",
                  "archive": "target/multi-target-signed-lib-0.1.0.jar",
                  "archiveSha256": "sha256:%s"
                }
                """.formatted(sha256(Files.readAllBytes(artifact))));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        try (PublishUploadServiceSigningTest.Recorder repositoryA =
                        PublishUploadServiceSigningTest.Recorder.start();
                PublishUploadServiceSigningTest.Recorder repositoryB =
                        PublishUploadServiceSigningTest.Recorder.start()) {
            Function<String, String> environment = Map.of(
                    "ZOLT_SIGNING_PASS", PASSPHRASE,
                    "GNUPGHOME", gnupgHome.toString())::get;
            PublishUploadService service = new PublishUploadService(
                    new PublishDryRunService(environment),
                    new ManifestProjectConfigLoader(),
                    new ManifestPublishSettingsLoader(),
                    new MavenRepositoryClient(),
                    environment);
            String coordinate = "com.example:multi-target-signed-lib:0.1.0";
            String repositoryPath =
                    "com/example/multi-target-signed-lib/0.1.0/multi-target-signed-lib-0.1.0.jar.asc";
            String requestPath = "/" + repositoryPath;
            String failedRequestPath = requestPath + ".sha256";
            Path stagingRoot = projectDir.resolve("target/publish/publish-staging");

            writeConfig(projectDir, repositoryA.baseUri());
            PublishTestPackageEvidence.write(projectDir);
            repositoryA.failPutPathSuffix = failedRequestPath;
            assertThrows(PublishException.class, () -> service.upload(projectDir));
            byte[] originalSignature = repositoryA.body(requestPath);
            Path transactionA = PublicationTransactionManifest.transactionPath(
                    stagingRoot, repositoryA.baseUri().normalize().toString(), coordinate);
            Path stagedSignatureA = PublicationTransactionManifest.transactionFilesPath(
                            stagingRoot, repositoryA.baseUri().normalize().toString(), coordinate)
                    .resolve(repositoryPath);
            assertArrayEquals(originalSignature, Files.readAllBytes(stagedSignatureA));

            writeConfig(projectDir, repositoryB.baseUri());
            repositoryB.failPutPathSuffix = failedRequestPath;
            assertThrows(PublishException.class, () -> service.upload(projectDir));
            Path transactionB = PublicationTransactionManifest.transactionPath(
                    stagingRoot, repositoryB.baseUri().normalize().toString(), coordinate);
            Path stagedSignatureB = PublicationTransactionManifest.transactionFilesPath(
                            stagingRoot, repositoryB.baseUri().normalize().toString(), coordinate)
                    .resolve(repositoryPath);
            assertTrue(Files.isRegularFile(transactionB));
            assertFalse(stagedSignatureA.equals(stagedSignatureB));
            assertArrayEquals(originalSignature, Files.readAllBytes(stagedSignatureA));

            writeConfig(projectDir, repositoryA.baseUri());
            repositoryA.failPutPathSuffix = null;
            service.upload(projectDir);

            assertArrayEquals(originalSignature, repositoryA.body(requestPath));
            assertEquals(1, repositoryA.putCount(requestPath));
            assertEquals(
                    sha256(originalSignature),
                    new String(repositoryA.body(failedRequestPath), StandardCharsets.UTF_8).trim());
            assertFalse(Files.exists(transactionA));
            assertFalse(Files.exists(transactionA.getParent()));
            assertTrue(Files.exists(transactionB));
            assertTrue(Files.isRegularFile(stagedSignatureB));
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

    private static boolean generateSigningKey(Path gnupgHome) throws IOException, InterruptedException {
        return runGpg(gnupgHome, List.of(
                "--batch",
                "--pinentry-mode", "loopback",
                "--passphrase", PASSPHRASE,
                "--quick-generate-key", "Zolt Target Test <target@zolt.test>", "default", "sign", "0")) == 0;
    }

    private static boolean gpgAvailable() {
        try {
            return runGpg(null, List.of("--version")) == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int runGpg(Path gnupgHome, List<String> arguments) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                        java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of("gpg"),
                                        arguments.stream())
                                .toList())
                .redirectErrorStream(true);
        if (gnupgHome != null) {
            builder.environment().put("GNUPGHOME", gnupgHome.toString());
        }
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    private static void writeConfig(Path projectDir, URI repository) throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "multi-target-signed-lib"
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
                """.formatted(Runtime.version().feature(), repository));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
