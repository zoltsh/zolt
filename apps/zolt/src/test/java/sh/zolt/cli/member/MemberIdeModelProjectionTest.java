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
 * {@code zolt ide model} from a workspace member directory, without {@code --workspace}.
 *
 * <p>An IDE opening {@code apps/api} asks for that project's model. The command exported it
 * standalone: it derived {@code apps/api/zolt.lock}, found nothing, and handed the IDE an empty
 * classpath plus a {@code LOCKFILE_MISSING} diagnostic — for a member whose dependencies are fully
 * resolved in the workspace root's lock. The machinery to do it right already existed for
 * {@code --workspace}; a member invocation must use it and return that member's model.
 */
final class MemberIdeModelProjectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberModelNamesTheWorkspaceLockAndCarriesARealClasspath() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("ide", "model", "--format", "json");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains(jsonPath(fixture.rootLock())),
                    () -> "the model names the authoritative workspace lock: " + result.stdout());
            assertFalse(result.stdout().contains(jsonPath(fixture.memberLock())),
                    () -> "and never a member-local one: " + result.stdout());
            assertFalse(result.stdout().contains("LOCKFILE_MISSING"), result.stdout());
            assertTrue(result.stdout().contains("api-only-1.0.0.jar"),
                    () -> "the member's own direct external is on its classpath: " + result.stdout());
        }
    }

    /**
     * The dependency list is this member's declarations, with the {@code { workspace = true }} provider
     * attributed to the member that provides it — and no sibling-only dependency anywhere.
     */
    @Test
    void memberModelListsItsOwnDependenciesWithWorkspaceAttribution() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("ide", "model", "--format", "json");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("\"coordinate\": \"" + MemberProjectionFixture.PROVIDER + "\""),
                    result.stdout());
            assertTrue(result.stdout().contains("\"workspace\": \"" + MemberProjectionFixture.CORE_MEMBER + "\""),
                    () -> "the provider is attributed to the member that provides it: " + result.stdout());
            assertTrue(result.stdout().contains("\"coordinate\": \"" + MemberProjectionFixture.API_ONLY + "\""),
                    result.stdout());
            assertFalse(result.stdout().contains(MemberProjectionFixture.UNRELATED_ONLY), result.stdout());
            assertFalse(result.stdout().contains("unrelated-only-1.0.0.jar"),
                    () -> "a sibling-only dependency is on no lane of this member: " + result.stdout());
        }
    }

    /**
     * The provider's own external is a real transitive dependency of this member at runtime, so it
     * belongs on the runtime and test lanes and not on compile — the same lane rule the classpath
     * projection follows. Without this the leak assertion above could be satisfied by a model that
     * simply dropped every transitive dependency.
     */
    @Test
    void memberModelKeepsTheProvidersTransitiveDependencyOffCompileAndOnRuntime() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.api("ide", "model", "--format", "json");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            String classpaths = result.stdout().substring(result.stdout().indexOf("\"classpaths\": {"));
            String compile = section(classpaths, "\"compile\": [", "]");
            String runtime = section(classpaths, "\"runtime\": [", "]");
            assertFalse(compile.contains("sibling-only-1.0.0.jar"), compile);
            assertTrue(runtime.contains("sibling-only-1.0.0.jar"), runtime);
            assertFalse(runtime.contains("unrelated-only-1.0.0.jar"), runtime);
        }
    }

    /**
     * Audit rule, workspace-root row: a root declares no {@code [project]}, so a project model there is
     * an actionable rejection. The IDE contract is a model carrying diagnostics rather than a non-zero
     * exit, so the rejection has to be legible IN the model — and name the invocation that works.
     */
    @Test
    void workspaceRootModelIsAnActionableRejection() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.root("ide", "model", "--format", "json");

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stdout().contains("WORKSPACE_ROOT_HAS_NO_PROJECT"), result.stdout());
            assertTrue(result.stdout().contains("declares no [project]"), result.stdout());
            assertTrue(result.stdout().contains("zolt ide model --workspace --format json"), result.stdout());
        }
    }

    /** The workspace-wide export still describes every member, canary included. */
    @Test
    void workspaceModelStillDescribesEveryMember() throws IOException {
        try (MemberProjectionFixture fixture = MemberProjectionFixture.create(tempDir)) {
            CommandResult result = fixture.root("ide", "model", "--workspace", "--format", "json");

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains(MemberProjectionFixture.UNRELATED_MEMBER), result.stdout());
            assertTrue(result.stdout().contains("unrelated-only-1.0.0.jar"), result.stdout());
            assertTrue(result.stdout().contains("api-only-1.0.0.jar"), result.stdout());
        }
    }

    /** A nested project the workspace never declares keeps the standalone export and its own lock. */
    @Test
    void nestedNonMemberProjectKeepsTheStandaloneExport() throws IOException {
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

            CommandResult result = fixture.in(outsider, "ide", "model", "--format", "json");

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stdout().contains("LOCKFILE_MISSING"),
                    () -> "a standalone project still answers about its own lock: " + result.stdout());
            assertTrue(result.stdout().contains(jsonPath(outsider.resolve("zolt.lock"))), result.stdout());
        }
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    private static String section(String json, String open, String close) {
        int start = json.indexOf(open);
        if (start < 0) {
            return "";
        }
        int end = json.indexOf(close, start);
        return end < 0 ? json.substring(start) : json.substring(start, end);
    }
}
