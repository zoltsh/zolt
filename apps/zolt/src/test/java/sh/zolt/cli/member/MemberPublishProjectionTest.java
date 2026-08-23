package sh.zolt.cli.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * {@code zolt publish} from a workspace member directory, across every mode.
 *
 * <p>The workspace-aware member route used to be exactly one command line:
 * {@code publish --dry-run --central}. Every other mode — a plain repository dry run, a plain live
 * upload, a Central upload, an attached SBOM, a release-context preflight — still planned against
 * {@code <member>/zolt.lock}, a file the workspace never creates. A publication is the most
 * consequential thing a member command emits, and it outlives the command: a POM that lists a
 * sibling's dependencies, or an SBOM attesting to packages the artifact never contained, is wrong
 * evidence sitting in a repository. Every mode has to plan from the same projection.
 */
final class MemberPublishProjectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberPlainDryRunPlansAPomFromTheWorkspaceLock() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            packageMember(fixture);

            CommandResult result = fixture.api("publish", "--dry-run");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("com.example:api:1.0.0"), result.stdout());
            assertPomIsTheMemberProjection(fixture);
        }
    }

    /**
     * The attached SBOM is the member's own closure. This is the mode where a whole-lock leak becomes
     * a signed, uploaded artifact claiming the wrong contents.
     */
    @Test
    void memberSbomAttachmentDescribesTheMemberClosure() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            packageMember(fixture);

            CommandResult result = fixture.api("publish", "--dry-run", "--sbom");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("cyclonedx"), result.stdout());
            String sbom = Files.readString(
                    fixture.apiDir().resolve("target/publish/api-1.0.0-cyclonedx.json"));
            assertTrue(sbom.contains(MemberProjectionFixture.API_ONLY.replace(':', '/')), sbom);
            assertTrue(sbom.contains(MemberProjectionFixture.SIBLING_ONLY.replace(':', '/')),
                    () -> "the provider's external reaches this member transitively: " + sbom);
            assertFalse(sbom.contains(MemberProjectionFixture.UNRELATED_ONLY.replace(':', '/')),
                    () -> "a sibling-only dependency must not be attested as published content: " + sbom);
        }
    }

    /** The irreversible mode: what actually lands in the repository is the member's projection. */
    @Test
    void memberLiveUploadPublishesThePomPlannedFromTheWorkspaceLock() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            packageMember(fixture);

            CommandResult result = fixture.api("publish");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("Status: uploaded"), result.stdout());
            String pom = new String(
                    fixture.repository().uploaded("/maven2/com/example/api/1.0.0/api-1.0.0.pom"),
                    StandardCharsets.UTF_8);
            assertTrue(pom.contains("<artifactId>api-only</artifactId>"), pom);
            assertTrue(pom.contains("<artifactId>core</artifactId>"), pom);
            assertFalse(pom.contains("unrelated-only"),
                    () -> "a sibling-only dependency must never be published as this member's: " + pom);
        }
    }

    /** The Central dry run — the one mode that was already routed — keeps working. */
    @Test
    void memberCentralDryRunStillPlansFromTheWorkspaceLock() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            packageMember(fixture);

            CommandResult result = fixture.api("publish", "--dry-run", "--central");

            assertTrue(result.stdout().contains("Maven Central readiness:"), result.stdout());
            assertTrue(result.stdout().contains("com.example:api:1.0.0"), result.stdout());
            assertPomIsTheMemberProjection(fixture);
        }
    }

    /**
     * The release-context preflight judges the member's policy-merged config — the one the plan was
     * built from — not a re-read of the member's raw manifest.
     */
    @Test
    void memberReleaseContextJudgesTheMergedMemberConfig() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            MemberProjectionFixture.addReleaseMetadata(fixture.apiDir());
            packageMember(fixture);

            CommandResult result = fixture.api("publish", "--dry-run", "--context", "release");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("Context: release"), result.stdout());
            assertFalse(result.stdout().contains("release context requires [project].description"),
                    result.stdout());
            assertPomIsTheMemberProjection(fixture);
        }
    }

    /**
     * A member with no {@code [publish]} at all is told exactly that — not told which repository key its
     * version would have needed. The configuration rejection has to come before planning, the way the
     * single-project planner orders it; otherwise routing a member through the workspace planner
     * silently downgrades the message every unpublishable member sees.
     */
    @Test
    void memberWithoutPublishConfigurationIsRejectedBeforeAnythingIsPlanned() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            CommandResult result = fixture.in(fixture.unrelatedDir(), "publish", "--dry-run");

            assertEquals(1, result.exitCode(), result.stdout());
            assertTrue(result.stderr().contains("No [publish] configuration found."), result.stderr());
            assertFalse(result.stderr().contains("snapshotRepository"), result.stderr());
        }
    }

    /**
     * Audit rule, workspace-root row: a root declares no {@code [project]}, so a single-project publish
     * there is an actionable rejection naming {@code --workspace}.
     */
    @Test
    void workspaceRootRejectsASingleProjectPublish() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            CommandResult result = fixture.root("publish", "--dry-run");

            assertEquals(1, result.exitCode(), result.stdout());
            assertTrue(result.stderr().contains("declares no [project]"), result.stderr());
            assertTrue(result.stderr().contains("--workspace"), result.stderr());
        }
    }

    /** A nested project the workspace never declares keeps the standalone planner and its own lock. */
    @Test
    void nestedNonMemberProjectKeepsTheStandalonePublishPath() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.createPublishable(tempDir)) {
            Path outsider = fixture.workspaceDir().resolve("tools/outsider");
            Files.createDirectories(outsider);
            Files.writeString(outsider.resolve("zolt.toml"), """
                    [project]
                    name = "outsider"
                    version = "1.0.0"
                    group = "com.example"
                    java = %s

                    [publish]
                    release = "company-releases"

                    [publish.repositories.company-releases]
                    url = "%s"
                    """.formatted(Runtime.version().feature(), fixture.repository().baseUri()));

            CommandResult result = fixture.in(outsider, "publish", "--dry-run");

            assertEquals(1, result.exitCode(), result.stdout());
            assertTrue(result.stderr().contains("zolt.lock"),
                    () -> "a standalone project still answers about its own lock: " + result.stderr());
        }
    }

    /** The generated POM lists the member's declared roots, and nothing a sibling declared. */
    private static void assertPomIsTheMemberProjection(MemberProjectionFixture fixture) throws IOException {
        String pom = Files.readString(fixture.apiDir().resolve("target/publish/api-1.0.0.pom"));
        assertTrue(pom.contains("<artifactId>api-only</artifactId>"), pom);
        assertTrue(pom.contains("<artifactId>core</artifactId>"),
                () -> "the { workspace = true } provider is a published dependency: " + pom);
        assertFalse(pom.contains("unrelated-only"),
                () -> "a sibling-only dependency is not this member's to declare: " + pom);
    }

    private static void packageMember(MemberProjectionFixture fixture) {
        CommandResult packaged = fixture.api(
                "package", "--workspace", "--member", MemberProjectionFixture.API_MEMBER);
        assertEquals(0, packaged.exitCode(), packaged.stdout() + packaged.stderr());
    }
}
