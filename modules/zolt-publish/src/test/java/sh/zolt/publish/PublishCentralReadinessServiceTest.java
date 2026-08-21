package sh.zolt.publish;

import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confirms the Central readiness check consumes {@link SourceDateEpoch} exactly as the signer does:
 * a valid epoch with no pinned key flags the reproducible-signing requirement, an absent value omits
 * it, and a blank/malformed/negative value fails loudly instead of silently claiming reproducibility.
 */
final class PublishCentralReadinessServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void validSourceDateEpochWithoutPinnedKeyFlagsTheReproducibleSigningRequirement() throws IOException {
        Path root = writeProject("""
                [publish.signing]
                method = "gpg"
                """);

        List<PublishCentralRequirement> requirements = service(env("1700000000")).evaluate(root, plan());

        assertTrue(
                requirements.stream().anyMatch(r -> r.name().equals("reproducible signing key") && !r.satisfied()),
                requirements.toString());
    }

    @Test
    void absentSourceDateEpochLeavesTheReproducibleSigningRequirementOut() throws IOException {
        Path root = writeProject("""
                [publish.signing]
                method = "gpg"
                """);

        List<PublishCentralRequirement> requirements = service(env(null)).evaluate(root, plan());

        assertTrue(
                requirements.stream().noneMatch(r -> r.name().equals("reproducible signing key")),
                requirements.toString());
    }

    @Test
    void blankSourceDateEpochFailsReadinessLoudly() throws IOException {
        Path root = writeProject("""
                [publish.signing]
                method = "gpg"
                """);

        PublishException exception =
                assertThrows(PublishException.class, () -> service(env("   ")).evaluate(root, plan()));

        assertActionable(exception);
    }

    @Test
    void malformedSourceDateEpochFailsReadinessLoudly() throws IOException {
        Path root = writeProject("""
                [publish.signing]
                method = "gpg"
                """);

        PublishException exception =
                assertThrows(PublishException.class, () -> service(env("not-an-epoch")).evaluate(root, plan()));

        assertActionable(exception);
        assertTrue(exception.getMessage().contains("not-an-epoch"), exception.getMessage());
    }

    @Test
    void negativeSourceDateEpochFailsReadinessLoudly() throws IOException {
        Path root = writeProject("""
                [publish.signing]
                method = "gpg"
                """);

        PublishException exception =
                assertThrows(PublishException.class, () -> service(env("-5")).evaluate(root, plan()));

        assertActionable(exception);
    }

    @Test
    void malformedSourceDateEpochIsIgnoredWhenSigningIsDisabled() throws IOException {
        // The epoch only gates reproducible SIGNING; with no [publish.signing] the parser is never
        // consulted, so a malformed value must not fail an otherwise fine readiness check.
        Path root = writeProject("");

        List<PublishCentralRequirement> requirements = service(env("not-an-epoch")).evaluate(root, plan());

        assertTrue(
                requirements.stream().noneMatch(r -> r.name().equals("reproducible signing key")),
                requirements.toString());
    }

    private static void assertActionable(PublishException exception) {
        String message = exception.getMessage();
        assertTrue(message.contains(SourceDateEpoch.ENV_NAME), message);
        assertTrue(message.contains("Next:"), message);
    }

    private static PublishCentralReadinessService service(UnaryOperator<String> environment) {
        return new PublishCentralReadinessService(new ManifestProjectConfigLoader(), new ManifestPublishSettingsLoader(), environment);
    }

    private static UnaryOperator<String> env(String sourceDateEpoch) {
        return name -> SourceDateEpoch.ENV_NAME.equals(name) ? sourceDateEpoch : null;
    }

    private Path writeProject(String publishBody) throws IOException {
        Path root = tempDir.resolve("readiness");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        String toml = """
                [project]
                name = "readiness-lib"
                version = "0.1.0"
                group = "com.example"
                java = %d
                """.formatted(Runtime.version().feature()) + "\n" + publishBody;
        Files.writeString(root.resolve("zolt.toml"), toml);
        return root;
    }

    private static PublishDryRunPlan plan() {
        PublishArtifactPlan sources = new PublishArtifactPlan(
                "sources",
                Optional.of("sources"),
                Path.of("target/app-0.1.0-sources.jar"),
                "sha256:sources",
                "com/example/app/0.1.0/app-0.1.0-sources.jar");
        return new PublishDryRunPlan(
                "com.example:app:0.1.0",
                "release",
                "central",
                "https://central.sonatype.com",
                "main",
                Path.of("target/app-0.1.0.jar"),
                "sha256:main",
                "com/example/app/0.1.0/app-0.1.0.jar",
                List.of(sources),
                Path.of("target/app-0.1.0.jar.zolt-package.json"),
                Path.of("target/publish/app-0.1.0.pom"),
                "sha256:pom",
                "com/example/app/0.1.0/app-0.1.0.pom",
                List.of(),
                "",
                List.of(),
                false);
    }
}
