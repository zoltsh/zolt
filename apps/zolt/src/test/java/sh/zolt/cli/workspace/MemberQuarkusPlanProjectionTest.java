package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * {@code zolt quarkus plan} from a workspace member directory.
 *
 * <p>The plan service resolved {@code zolt.lock} against the project directory it was handed, which is
 * right for a standalone project and wrong for a member: the member holds no lock, so the plan failed
 * on a file that exists at the workspace root — and if a stray one WAS present it planned the
 * augmentation from that instead. A member plans from its projection of the root lock — its own
 * runtime and Quarkus-deployment closure, the same one the workspace packager uses — so the
 * augmentation inputs it prints are the inputs its build will actually see.
 */
final class MemberQuarkusPlanProjectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberQuarkusPlanReadsTheWorkspaceLockAndProjectsTheMemberClosure() throws IOException {
        MemberQuarkusFixture fixture = MemberQuarkusFixture.create(tempDir);

        CommandResult result = plan(fixture, fixture.quarkusMember());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains(MemberQuarkusFixture.RUNTIME_JAR),
                () -> "the member's own runtime extension: " + result.stdout());
        assertTrue(result.stdout().contains(MemberQuarkusFixture.DEPLOYMENT_JAR),
                () -> "and its deployment artifact: " + result.stdout());
        assertFalse(result.stdout().contains(MemberQuarkusFixture.SIBLING_ONLY_JAR),
                () -> "a sibling-only dependency is on no lane of this member: " + result.stdout());
        assertFalse(Files.exists(fixture.memberLock()), "a plan never creates a member-local lock");
    }

    /** Audit rule, workspace-root row: a root declares no {@code [project]} — actionable rejection. */
    @Test
    void workspaceRootRejectsAQuarkusPlan() throws IOException {
        MemberQuarkusFixture fixture = MemberQuarkusFixture.create(tempDir);

        CommandResult result = plan(fixture, fixture.workspaceDir());

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stderr().contains("declares no [project]"), result.stderr());
        assertTrue(result.stderr().contains("--workspace"), result.stderr());
    }

    /** A nested project the workspace never declares keeps the standalone plan and its own lock. */
    @Test
    void nestedNonMemberProjectKeepsTheStandalonePlanPath() throws IOException {
        MemberQuarkusFixture fixture = MemberQuarkusFixture.create(tempDir);
        Path outsider = fixture.workspaceDir().resolve("tools/outsider");
        Files.createDirectories(outsider);
        Files.writeString(outsider.resolve("zolt.toml"), """
                [project]
                name = "outsider"
                version = "1.0.0"
                group = "com.example"
                java = %s

                [package]
                mode = "quarkus"
                """.formatted(Runtime.version().feature()));

        CommandResult result = plan(fixture, outsider);

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stderr().contains(outsider.resolve("zolt.lock").toString()),
                () -> "a standalone project still answers about its own lock: " + result.stderr());
    }

    private static CommandResult plan(MemberQuarkusFixture fixture, Path directory) {
        return execute("quarkus", "plan",
                "--cwd", directory.toString(),
                "--cache-root", fixture.cacheRoot().toString());
    }
}
