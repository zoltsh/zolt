package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.ContentAddressedLockTestSupport;

/**
 * Design §4.5/§6.8: a command started inside a workspace member composes that member with the
 * workspace root and runs through the workspace, whose single root lock is the only one in play.
 *
 * <p>Each case here fails in a different way when a command takes the standalone path from a member
 * directory: it resolves a member-local lock into existence, compiles against a lock that never knew
 * about the workspace providers, or silently consumes a member-local file a standalone command left
 * behind. The two parameterized cases carry the invariant across every command at once, so a command
 * added later cannot quietly opt out of it.
 */
final class MemberDirectoryCommandRoutingTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberBuildUsesRootLockAndBuildsWorkspaceProviders() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "build");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(fixture.apiClass()), "the started-in member is built");
        assertTrue(
                Files.exists(fixture.coreClass()),
                "the workspace provider it compiles against is built first");
        assertFalse(
                Files.exists(fixture.unrelatedClass()),
                "a member outside this member's closure is not built");
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberRunUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "run");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("api:core"), result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberTestUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "test");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("apps/api"), result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberTestCompileUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "test", "--compile-only");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                Files.exists(fixture.apiDir().resolve("target/test-classes/com/example/api/ApiTest.class")),
                result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberIntegrationTestUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "integration-test");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("apps/api"), result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberPackageUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "package");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(fixture.apiDir().resolve("target/api-0.1.0.jar")), result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberRunPackageUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, "run-package");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("api:core"), result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    @Test
    void memberNativeUsesRootLock() throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);
        Path nativeImage = MemberDirectoryFixture.writeFakeNativeImage(
                tempDir.resolve("tools/native-image"));

        CommandResult result = member(fixture, "native", "--native-image", nativeImage.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(fixture.apiDir().resolve("target/native/api")), result.stdout());
        assertFalse(Files.exists(fixture.memberLock()));
    }

    /**
     * A member-local lock whose project fingerprint MATCHES the member config is the dangerous one:
     * the standalone freshness gate accepts it without complaint and every later step reads it. It
     * names only a package no member depends on, so consuming it fails the compile it governs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memberCommands")
    void memberCommandIgnoresMatchingMemberLocalLock(String name, List<String> arguments) throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);
        ContentAddressedLockTestSupport.write(fixture.memberLock(), fixture.cacheRoot(), poisonedLock());
        String planted = Files.readString(fixture.memberLock());

        CommandResult result = member(fixture, arguments(fixture, arguments));

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().contains("poison"), result.stdout());
        assertFalse(result.stderr().contains("poison"), result.stderr());
        assertEquals(
                planted,
                Files.readString(fixture.memberLock()),
                "the member-local lock is ignored, never consumed or rewritten");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memberCommands")
    void memberCommandNeverCreatesMemberLocalLock(String name, List<String> arguments) throws IOException {
        MemberDirectoryFixture.Fixture fixture = MemberDirectoryFixture.create(tempDir);

        CommandResult result = member(fixture, arguments(fixture, arguments));

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(
                Files.exists(fixture.memberLock()),
                () -> name + " created a member-local lock at " + fixture.memberLock());
        assertTrue(Files.exists(fixture.rootLock()));
    }

    private static Stream<Arguments> memberCommands() {
        return Stream.of(
                Arguments.of("build", List.of("build")),
                Arguments.of("run", List.of("run")),
                Arguments.of("test", List.of("test")),
                Arguments.of("test --compile-only", List.of("test", "--compile-only")),
                Arguments.of("integration-test", List.of("integration-test")),
                Arguments.of("package", List.of("package")),
                Arguments.of("run-package", List.of("run-package")),
                Arguments.of("native", List.of("native", "--native-image")),
                Arguments.of("classpath compile", List.of("classpath", "compile")));
    }

    /** Fills in the one argument a case cannot know until its fixture exists. */
    private List<String> arguments(MemberDirectoryFixture.Fixture fixture, List<String> arguments)
            throws IOException {
        if (!arguments.contains("--native-image")) {
            return arguments;
        }
        List<String> filled = new ArrayList<>(arguments);
        filled.add(MemberDirectoryFixture.writeFakeNativeImage(
                tempDir.resolve("tools/native-image")).toString());
        return filled;
    }

    private static CommandResult member(MemberDirectoryFixture.Fixture fixture, String... arguments) {
        return member(fixture, List.of(arguments));
    }

    private static CommandResult member(MemberDirectoryFixture.Fixture fixture, List<String> arguments) {
        List<String> command = new ArrayList<>(arguments);
        command.add("--cwd");
        command.add(fixture.apiDir().toString());
        command.add("--cache-root");
        command.add(fixture.cacheRoot().toString());
        return execute(command.toArray(String[]::new));
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


}
