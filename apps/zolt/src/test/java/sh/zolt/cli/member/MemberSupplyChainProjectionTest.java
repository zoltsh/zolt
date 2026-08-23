package sh.zolt.cli.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * {@code zolt sbom} and {@code zolt licenses} from a workspace member directory.
 *
 * <p>Finding the workspace root's lock is only half the rule. That lock is the WHOLE workspace's
 * resolution, so emitting it from a member directory attests that this member depends on every
 * package any sibling depends on — in an SBOM, which is supply-chain evidence, and in a license
 * report, which is what a compliance review reads. Both must describe the member's own reachable
 * closure instead.
 *
 * <p>The canary is {@code com.example:unrelated-only}, declared by {@code libs/unrelated} alone, and
 * the only {@code GPL-3.0-only} artifact in the workspace. The controls are {@code api-only} (a direct
 * external), {@code core} (the {@code { workspace = true }} provider), and {@code sibling-only} (the
 * provider's own external, which really does reach this member transitively).
 */
final class MemberSupplyChainProjectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberSbomDescribesTheMemberClosureAndNotASiblingOnlyDependency() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("sbom", "--offline", "--format", "cyclonedx");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains(MemberProjectionFixture.API_ONLY.replace(':', '/')),
                    () -> "the member's own direct external: " + result.stdout());
            assertTrue(result.stdout().contains(MemberProjectionFixture.SIBLING_ONLY.replace(':', '/')),
                    () -> "the provider's external reaches this member transitively: " + result.stdout());
            assertTrue(result.stdout().contains(MemberProjectionFixture.PROVIDER.replace(':', '/')),
                    () -> "the { workspace = true } provider is a component of this member: "
                            + result.stdout());
            assertFalse(result.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY.replace(':', '/')),
                    () -> "a sibling-only dependency must not be attested as this member's: "
                            + result.stdout());
        }
    }

    /**
     * The member SBOM's root component is the MEMBER, not the workspace and not a sibling; a report
     * whose components were right but whose subject was wrong would still be wrong evidence.
     */
    @Test
    void memberSbomNamesTheMemberAsItsSubject() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("sbom", "--offline", "--format", "cyclonedx");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("\"purl\": \"pkg:maven/com.example/api@1.0.0"),
                    () -> "metadata.component is the member: " + result.stdout());
        }
    }

    @Test
    void memberLicenseReportCoversTheMemberClosureOnly() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("licenses", "--offline");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains(MemberProjectionFixture.API_ONLY_LICENSE), result.stdout());
            assertTrue(result.stdout().contains(MemberProjectionFixture.API_ONLY), result.stdout());
            assertTrue(result.stdout().contains(MemberProjectionFixture.SIBLING_ONLY_LICENSE), result.stdout());
            assertTrue(result.stdout().contains(MemberProjectionFixture.SIBLING_ONLY), result.stdout());
            assertFalse(result.stdout().contains(MemberProjectionFixture.LEAKED_LICENSE),
                    () -> "a sibling-only dependency's license must not be reported here: "
                            + result.stdout());
            assertFalse(result.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY), result.stdout());
        }
    }

    /**
     * The sibling that DOES own the canary still reports it. Without this, a projection that simply
     * dropped the package everywhere would pass the leak assertions above.
     */
    @Test
    void theOwningSiblingStillReportsItsOwnDependency() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.in(fixture.unrelatedDir(), "licenses", "--offline");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains(MemberProjectionFixture.LEAKED_LICENSE), result.stdout());
            assertTrue(result.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY), result.stdout());
            assertFalse(result.stdout().contains(MemberProjectionFixture.API_ONLY), result.stdout());
        }
    }

    /**
     * Audit rule, workspace-root row: a root declares no {@code [project]}, so a single-project report
     * there is an actionable rejection naming {@code --workspace}, never an empty or whole-lock report.
     */
    @Test
    void workspaceRootRejectsSingleProjectSupplyChainReports() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult sbom = fixture.root("sbom", "--offline", "--format", "cyclonedx");
            CommandResult licenses = fixture.root("licenses", "--offline");

            assertEquals(1, sbom.exitCode(), sbom.stdout());
            assertTrue(sbom.stderr().contains("declares no [project]"), sbom.stderr());
            assertTrue(sbom.stderr().contains("--workspace"), sbom.stderr());
            assertEquals(1, licenses.exitCode(), licenses.stdout());
            assertTrue(licenses.stderr().contains("declares no [project]"), licenses.stderr());
            assertTrue(licenses.stderr().contains("--workspace"), licenses.stderr());
        }
    }

    /**
     * The workspace-wide reports are unchanged by member projection: they still aggregate every
     * member, canary included. This is the control that the member fix narrowed only the member view.
     */
    @Test
    void workspaceWideReportsStillAggregateEveryMember() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult sbom = fixture.root("sbom", "--workspace", "--offline", "--format", "cyclonedx");
            CommandResult licenses = fixture.root("licenses", "--workspace", "--offline");

            assertEquals(0, sbom.exitCode(), sbom.stdout() + sbom.stderr());
            assertTrue(sbom.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY.replace(':', '/')),
                    sbom.stdout());
            assertTrue(sbom.stdout().contains(MemberProjectionFixture.API_ONLY.replace(':', '/')),
                    sbom.stdout());
            assertEquals(0, licenses.exitCode(), licenses.stdout() + licenses.stderr());
            assertTrue(licenses.stdout().contains(MemberProjectionFixture.LEAKED_LICENSE), licenses.stdout());
            assertTrue(licenses.stdout().contains(MemberProjectionFixture.API_ONLY_LICENSE), licenses.stdout());
        }
    }
}
