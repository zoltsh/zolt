package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.ContentAddressedLockTestSupport;

/**
 * {@code zolt quarkus plan} from a workspace member directory.
 *
 * <p>The plan service resolved {@code zolt.lock} against the project directory it was handed, which is
 * right for a standalone project and wrong for a member: the member holds no lock, so the plan failed
 * on a file that exists at the workspace root. A member plans from its projection of that root lock —
 * its own runtime and Quarkus-deployment closure, the same one the workspace packager uses — so the
 * augmentation inputs it prints are the inputs its build will actually see.
 */
final class MemberQuarkusPlanProjectionTest {
    private static final String QUARKUS_MEMBER = "apps/api";
    private static final String OTHER_MEMBER = "libs/unrelated";
    private static final String SIBLING_ONLY_JAR = "sibling-only-1.0.0.jar";

    @TempDir
    private Path tempDir;

    @Test
    void memberQuarkusPlanReadsTheWorkspaceLockAndProjectsTheMemberClosure() throws IOException {
        Fixture fixture = create();

        CommandResult result = plan(fixture, fixture.quarkusMember());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("quarkus-rest-3.33.0.jar"),
                () -> "the member's own runtime extension: " + result.stdout());
        assertTrue(result.stdout().contains("quarkus-rest-deployment-3.33.0.jar"),
                () -> "and its deployment artifact: " + result.stdout());
        assertFalse(result.stdout().contains(SIBLING_ONLY_JAR),
                () -> "a sibling-only dependency is on no lane of this member: " + result.stdout());
        assertFalse(Files.exists(fixture.memberLock()), "a plan never creates a member-local lock");
    }

    /**
     * A valid, fingerprint-matching member-local lock naming a package no member depends on changes
     * nothing: it is neither read nor rewritten.
     */
    @Test
    void aPlantedMemberLocalLockChangesNothing() throws IOException {
        Fixture clean = create();
        String expected = plan(clean, clean.quarkusMember()).stdout();

        Fixture poisoned = create();
        ContentAddressedLockTestSupport.write(poisoned.memberLock(), poisoned.cacheRoot(), poisonedLock());
        String planted = Files.readString(poisoned.memberLock());

        CommandResult result = plan(poisoned, poisoned.quarkusMember());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertFalse(result.stdout().contains("poison"), result.stdout());
        assertEquals(
                expected.replace(clean.workspaceDir().toString(), ""),
                result.stdout().replace(poisoned.workspaceDir().toString(), ""),
                "the planted member-local lock is observationally irrelevant");
        assertEquals(
                planted,
                Files.readString(poisoned.memberLock()),
                "and is neither consumed nor rewritten");
    }

    /** Audit rule, workspace-root row: a root declares no {@code [project]} — actionable rejection. */
    @Test
    void workspaceRootRejectsAQuarkusPlan() throws IOException {
        Fixture fixture = create();

        CommandResult result = plan(fixture, fixture.workspaceDir());

        assertEquals(1, result.exitCode(), result.stdout());
        assertTrue(result.stderr().contains("declares no [project]"), result.stderr());
        assertTrue(result.stderr().contains("--workspace"), result.stderr());
    }

    /** A nested project the workspace never declares keeps the standalone plan and its own lock. */
    @Test
    void nestedNonMemberProjectKeepsTheStandalonePlanPath() throws IOException {
        Fixture fixture = create();
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

    private static CommandResult plan(Fixture fixture, Path directory) {
        return execute("quarkus", "plan",
                "--cwd", directory.toString(),
                "--cache-root", fixture.cacheRoot().toString());
    }

    private Fixture create() throws IOException {
        Path workspaceDir = Files.createTempDirectory(tempDir, "workspace");
        Path cacheRoot = workspaceDir.resolveSibling(workspaceDir.getFileName() + "-cache");
        Path quarkusMember = workspaceDir.resolve(QUARKUS_MEMBER);
        Path otherMember = workspaceDir.resolve(OTHER_MEMBER);
        Files.createDirectories(quarkusMember);
        Files.createDirectories(otherMember);
        emptyJar(cacheRoot.resolve("io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar"));
        emptyJar(cacheRoot.resolve(
                "io/quarkus/quarkus-rest-deployment/3.33.0/quarkus-rest-deployment-3.33.0.jar"));
        emptyJar(cacheRoot.resolve("com/example/sibling-only/1.0.0/" + SIBLING_ONLY_JAR));

        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "family"

                [workspace.members]
                include = ["apps/api", "libs/unrelated"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = %s
                """.formatted(Runtime.version().feature()));
        Files.writeString(quarkusMember.resolve("zolt.toml"), """
                [project]
                name = "api"
                main = "com.example.api.Main"

                [package]
                mode = "quarkus"
                """);
        Files.writeString(otherMember.resolve("zolt.toml"), """
                [project]
                name = "unrelated"
                """);
        ContentAddressedLockTestSupport.write(workspaceDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "libs/unrelated"
                id = "com.example:sibling-only"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar"
                dependencies = []
                members = ["apps/api"]

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.0/quarkus-rest-deployment-3.33.0.jar"
                dependencies = []
                members = ["apps/api"]

                [[package]]
                id = "com.example:sibling-only"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/sibling-only/1.0.0/%s"
                dependencies = []
                members = ["libs/unrelated"]
                """.formatted(SIBLING_ONLY_JAR));
        return new Fixture(workspaceDir, cacheRoot);
    }

    private static String poisonedLock() {
        return """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:poison"
                version = "9.9.9"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:poison"
                version = "9.9.9"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/poison/9.9.9/poison-9.9.9.jar"
                dependencies = []
                """;
    }

    private static void emptyJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.flush();
        }
    }

    private record Fixture(Path workspaceDir, Path cacheRoot) {
        Path quarkusMember() {
            return workspaceDir.resolve(QUARKUS_MEMBER);
        }

        Path memberLock() {
            return quarkusMember().resolve("zolt.lock");
        }
    }
}
