package sh.zolt.cli.command.dependency;

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
 * Design §4.5 "Named maps": workspace versions are available to every member, and a root-owned alias
 * may not be redeclared in a member. A mutation command run inside a member therefore validates
 * {@code --version-ref} against the root-merged alias set, and writes the declaration to the member
 * manifest that owns it.
 */
final class MemberVersionAliasCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void addAcceptsARootOwnedVersionAliasFromAMemberDirectory() throws IOException {
        Path member = workspace();

        CommandResult result = execute(
                "add", "com.example:core",
                "--version-ref", "shared",
                "--no-resolve",
                "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        String memberToml = Files.readString(member.resolve("zolt.toml"));
        assertTrue(memberToml.contains("versionRef = \"shared\""), memberToml);
        assertFalse(memberToml.contains("[versions]"), () -> "the root alias must not be redeclared: " + memberToml);
        assertTrue(
                Files.readString(member.getParent().getParent().resolve("zolt.toml")).contains("shared"),
                "the root keeps sole ownership of the alias");
    }

    @Test
    void addStillRejectsAnAliasNoManifestDeclaresAndDoesNotAskForARedeclaration() throws IOException {
        Path member = workspace();

        CommandResult result = execute(
                "add", "com.example:core",
                "--version-ref", "missing",
                "--no-resolve",
                "--cwd", member.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown versionRef `missing`"), result.stderr());
        assertTrue(
                result.stderr().contains("in this member or in the workspace root"),
                () -> "the remedy must not instruct a forbidden redeclaration: " + result.stderr());
    }

    @Test
    void platformsSetAcceptsARootOwnedVersionAliasFromAMemberDirectory() throws IOException {
        Path member = workspace();

        CommandResult result = execute(
                "platforms", "set", "com.example:platform",
                "--version-ref", "shared",
                "--no-resolve",
                "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                Files.readString(member.resolve("zolt.toml")).contains("versionRef = \"shared\""),
                Files.readString(member.resolve("zolt.toml")));
    }

    private Path workspace() throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21

                [versions]
                shared = "1.2.3"
                """);
        Path member = root.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        return member;
    }
}
