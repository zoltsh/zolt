package sh.zolt.publish;

import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Design §14.4 leaves the POM display name with no authored spelling, so a complete
     * {@code [project]} has to satisfy Central's name requirement on its own — Sonatype rejects a POM
     * without {@code <name>}, and no manifest edit could add one.
     */
    @Test
    void completeProjectIdentitySatisfiesEveryCentralRequirement() throws IOException {
        Path root = tempDir.resolve("complete");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        Files.writeString(root.resolve("zolt.toml"), """
                [project]
                name = "readiness-lib"
                version = "1.0.0"
                group = "com.example"
                java = %d
                description = "A complete library."
                url = "https://example.test/readiness-lib"
                license = "Apache-2.0"
                issues = "https://example.test/readiness-lib/issues"

                [project.scm]
                url = "https://github.com/example/readiness-lib"
                connection = "scm:git:https://github.com/example/readiness-lib.git"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.test"

                [publish.signing]
                method = "gpg"
                """.formatted(Runtime.version().feature()));

        List<PublishCentralRequirement> requirements =
                service(env(null)).evaluate(root, completePlan());

        assertTrue(
                requirements.stream().anyMatch(r -> r.name().equals("project name") && r.satisfied()),
                requirements.toString());
        assertTrue(PublishCentralReadiness.allSatisfied(requirements), requirements.toString());
    }

    /**
     * Design §7.3: an SPDX expression has no derivable license URL, so readiness must say so instead
     * of reporting a project Central would reject as ready.
     */
    @Test
    void spdxExpressionWithoutAnExplicitUrlFailsTheLicenseRequirement() throws IOException {
        Path root = writeProject("""
                [publish.signing]
                method = "gpg"
                """, "license = { id = \"Apache-2.0 OR MIT\", name = \"Apache-2.0 or MIT\" }");

        PublishCentralRequirement license = service(env(null)).evaluate(root, plan()).stream()
                .filter(r -> r.name().equals("license name and url"))
                .findFirst()
                .orElseThrow();

        assertFalse(license.satisfied());
        assertTrue(license.remediation().contains("explicit name and url"), license.remediation());
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
        return writeProject(publishBody, "");
    }

    private Path writeProject(String publishBody, String projectBody) throws IOException {
        Path root = tempDir.resolve("readiness");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        String toml = """
                [project]
                name = "readiness-lib"
                version = "0.1.0"
                group = "com.example"
                java = %d
                """.formatted(Runtime.version().feature()) + projectBody + "\n\n" + publishBody;
        Files.writeString(root.resolve("zolt.toml"), toml);
        return root;
    }

    /** The dry-run plan of a release that already carries both supplemental jars Central requires. */
    private static PublishDryRunPlan completePlan() {
        PublishArtifactPlan javadoc = new PublishArtifactPlan(
                "javadoc",
                Optional.of("javadoc"),
                Path.of("target/app-1.0.0-javadoc.jar"),
                "sha256:javadoc",
                "com/example/app/1.0.0/app-1.0.0-javadoc.jar");
        PublishDryRunPlan base = plan();
        return new PublishDryRunPlan(
                base.coordinate(),
                base.versionKind(),
                base.repositoryId(),
                base.repositoryUrl(),
                base.artifactId(),
                base.artifactPath(),
                base.artifactSha256(),
                base.artifactUploadPath(),
                List.of(base.supplementalArtifacts().getFirst(), javadoc),
                base.evidencePath(),
                base.pomPath(),
                base.pomSha256(),
                base.pomUploadPath(),
                base.checksumSidecars(),
                base.context(),
                base.blockers(),
                base.pomOnly());
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
