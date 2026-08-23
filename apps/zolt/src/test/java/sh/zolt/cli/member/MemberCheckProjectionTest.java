package sh.zolt.cli.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * {@code zolt check} from a workspace member directory, without {@code --workspace}.
 *
 * <p>Project mode composed the member's config correctly and then evaluated it against
 * {@code <member>/zolt.lock} — a file the workspace never creates. Every lock-backed check therefore
 * reported a missing lock while the authoritative one sat at the workspace root, and any check that
 * did read a lock would have been reading facts for the wrong project. A member check is the
 * workspace check with exactly this member selected.
 */
final class MemberCheckProjectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberCheckReadsTheWorkspaceLockNotAMemberLocalOne() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("check", "--offline", "--check", "lockfile");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("ok lockfile"), result.stdout());
            assertFalse(result.stdout().contains("zolt.lock is missing"), result.stdout());
            assertFalse(Files.exists(fixture.memberLock()), "a check never creates a member-local lock");
        }
    }

    /**
     * The dependency-policy facts are the MEMBER's. A sibling-only dependency is not this member's
     * baseline to explain, and a member that inherited the whole lock would count it.
     */
    @Test
    void memberDependencyPolicyFactsExcludeASiblingOnlyDependency() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult api = fixture.api("check", "--offline", "--check", "dependency-policy", "--format", "json");
            CommandResult unrelated = fixture.in(
                    fixture.unrelatedDir(), "check", "--offline", "--check", "dependency-policy", "--format", "json");

            assertEquals(0, api.exitCode(), api.stdout() + api.stderr());
            assertTrue(api.stdout().contains(MemberProjectionFixture.API_MEMBER), api.stdout());
            assertFalse(api.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY), api.stdout());
            // The owning sibling still accounts for it, so the projection narrows rather than deletes.
            assertEquals(0, unrelated.exitCode(), unrelated.stdout() + unrelated.stderr());
            assertTrue(unrelated.stdout().contains(MemberProjectionFixture.UNRELATED_MEMBER), unrelated.stdout());
            assertFalse(unrelated.stdout().contains(MemberProjectionFixture.API_ONLY), unrelated.stdout());
        }
    }

    /**
     * A member's license policy is enforced over the member's own closure. The canary is denied
     * workspace-wide; only the member that actually reaches it may fail on it.
     */
    @Test
    void memberLicensePolicyEnforcesOverTheMemberClosureOnly() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            denyLeakedLicense(fixture.apiDir());
            denyLeakedLicense(fixture.unrelatedDir());

            CommandResult api = fixture.api("check", "--offline", "--check", "license-policy");
            CommandResult unrelated =
                    fixture.in(fixture.unrelatedDir(), "check", "--offline", "--check", "license-policy");

            assertEquals(0, api.exitCode(),
                    () -> "apps/api never reaches the denied license: " + api.stdout() + api.stderr());
            assertFalse(api.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY), api.stdout());
            assertEquals(1, unrelated.exitCode(),
                    () -> "libs/unrelated does reach it and must still fail: "
                            + unrelated.stdout() + unrelated.stderr());
            assertTrue(unrelated.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY), unrelated.stdout());
        }
    }

    /** A member check checks one project, so its human report stays project-shaped, member-qualified. */
    @Test
    void memberCheckStaysProjectShapedAndNamesTheMember() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("check", "--offline");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("Checking project"), result.stdout());
            assertFalse(result.stdout().contains("Checking workspace"), result.stdout());
            assertTrue(result.stdout().contains("command-surface " + MemberProjectionFixture.API_MEMBER),
                    result.stdout());
        }
    }

    /**
     * Audit rule, workspace-root row: a root declares no {@code [project]}, so a single-project check
     * there is an actionable rejection naming {@code --workspace}.
     */
    @Test
    void workspaceRootRejectsASingleProjectCheck() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.root("check", "--offline");

            assertEquals(1, result.exitCode(), result.stdout());
            assertTrue(result.stdout().contains("declares no [project]"), result.stdout());
            assertTrue(result.stdout().contains("--workspace"), result.stdout());
        }
    }

    /** A nested project the workspace never declares keeps the standalone path and its own lock. */
    @Test
    void nestedNonMemberProjectKeepsTheStandaloneCheckPath() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            Path outsider = fixture.workspaceDir().resolve("tools/outsider");
            Files.createDirectories(outsider);
            Files.writeString(outsider.resolve("zolt.toml"), """
                    [project]
                    name = "outsider"
                    version = "1.0.0"
                    group = "com.example"
                    java = %s
                    """.formatted(Runtime.version().feature()));

            CommandResult result = fixture.in(outsider, "check", "--offline", "--check", "lockfile");

            assertEquals(1, result.exitCode(), result.stdout());
            assertTrue(result.stdout().contains("zolt.lock is missing"), result.stdout());
            assertTrue(result.stdout().contains("Run `zolt resolve`"),
                    () -> "a standalone project is told to resolve standalone: " + result.stdout());
        }
    }

    private static void denyLeakedLicense(Path memberDir) throws IOException {
        Files.writeString(
                memberDir.resolve("zolt.toml"),
                Files.readString(memberDir.resolve("zolt.toml")) + """

                        [dependencies.policy.licenses]
                        deny = ["%s"]
                        """.formatted(MemberProjectionFixture.LEAKED_LICENSE));
    }
}
