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
import sh.zolt.cli.ContentAddressedLockTestSupport;

/**
 * Design §4.5/§6.8: a member command projects its answer from the one root lock — a SELECTION out of
 * the workspace-wide resolution, not the whole of it.
 *
 * <p>{@code zolt classpath} used to hand the complete workspace lock to the generic converter, which
 * emits every external package it finds and drops workspace records entirely. From {@code apps/api}
 * that produced both errors at once: a library only {@code libs/core} depends on appeared on the
 * classpath, and the compiled output of the direct {@code { workspace = true }} dependency did not.
 */
final class MemberClasspathProjectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberClasspathExcludesSiblingOnlyDependency() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = classpath(fixture, "compile");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("api-only-1.0.0.jar"), result.stdout());
        assertFalse(
                result.stdout().contains("sibling-only-1.0.0.jar"),
                () -> "libs/core declares that library as an implementation dependency, so it is off "
                        + "the consumer's compile lane: " + result.stdout());
        assertFalse(
                result.stdout().contains("unrelated-only-1.0.0.jar"),
                () -> "libs/unrelated is outside this member's closure: " + result.stdout());
    }

    @Test
    void memberClasspathIncludesDirectWorkspaceDependencyOutput() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = classpath(fixture, "compile");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains(fixture.coreDir().resolve("target/classes").toString()),
                () -> "the direct { workspace = true } dependency compiles against its output: "
                        + result.stdout());
    }

    @Test
    void memberTestClasspathUsesOnlyMemberClosure() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = classpath(fixture, "test");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("junit-platform-console-standalone-1.11.4.jar"), result.stdout());
        assertTrue(result.stdout().contains("api-only-1.0.0.jar"), result.stdout());
        assertTrue(
                result.stdout().contains(fixture.coreDir().resolve("target/classes").toString()),
                result.stdout());
        // A provider's implementation dependency IS on the consumer's runtime and test lanes.
        assertTrue(result.stdout().contains("sibling-only-1.0.0.jar"), result.stdout());
        assertFalse(
                result.stdout().contains("unrelated-only-1.0.0.jar"),
                () -> "a member outside this member's closure contributes to no lane of it: "
                        + result.stdout());
    }

    @Test
    void memberClasspathAuditReportsSelectedMember() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = classpath(fixture, "audit");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Member: apps/api"), result.stdout());
        assertTrue(result.stdout().contains("com.example:api-only:1.0.0"), result.stdout());
        assertTrue(result.stdout().contains("com.example:core:0.1.0"), result.stdout());
        assertFalse(
                result.stdout().contains("com.example:unrelated-only"),
                () -> "the audit describes this member's slice of the workspace resolution: "
                        + result.stdout());
    }

    /**
     * The same projection binds the other read-only report over the root lock: a member's package plan
     * describes the dependencies THIS member packages, not every package in the workspace.
     */
    @Test
    void memberPackagePlanReportsOnlyMemberClosure() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = execute(
                "package", "--plan",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", fixture.cacheRoot().toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("com.example:api-only:1.0.0"), result.stdout());
        assertFalse(
                result.stdout().contains("com.example:unrelated-only"),
                () -> "libs/unrelated is outside this member's closure: " + result.stdout());
    }

    /** A project outside every workspace still reports its own complete lock, member column and all. */
    @Test
    void standaloneClasspathBehaviorIsUnchanged() throws IOException {
        Path project = standaloneProject();

        CommandResult compile = execute(
                "classpath", "compile",
                "--cwd", project.toString(),
                "--cache-root", tempDir.resolve("standalone-cache").toString());
        CommandResult audit = execute(
                "classpath", "audit",
                "--cwd", project.toString(),
                "--cache-root", tempDir.resolve("standalone-cache").toString());

        assertEquals(0, compile.exitCode(), compile.stderr());
        assertTrue(compile.stdout().contains("solo-1.0.0.jar"), compile.stdout());
        assertEquals(0, audit.exitCode(), audit.stderr());
        assertTrue(audit.stdout().contains("com.example:solo:1.0.0"), audit.stdout());
        assertFalse(audit.stdout().contains("Member:"), audit.stdout());
    }

    private static CommandResult classpath(MemberDirectoryFixture.Fixture fixture, String lane) {
        return execute(
                "classpath", lane,
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", fixture.cacheRoot().toString());
    }

    private Path standaloneProject() throws IOException {
        Path project = tempDir.resolve("standalone");
        Path cacheRoot = tempDir.resolve("standalone-cache");
        Files.createDirectories(project);
        MemberDirectoryFixture.writeEmptyJar(
                cacheRoot.resolve("com/example/solo/1.0.0/solo-1.0.0.jar"));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "solo"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [dependencies]
                "com.example:solo" = "1.0.0"
                """.formatted(Runtime.version().feature()));
        ContentAddressedLockTestSupport.write(project.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:solo"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:solo"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/solo/1.0.0/solo-1.0.0.jar"
                dependencies = []
                """);
        return project;
    }
}
