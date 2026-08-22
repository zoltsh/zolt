package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaToolchainRequest;

/**
 * Design §4.5 "Command discovery": {@code zolt toolchain status|list|sync} and {@code zolt doctor}
 * read the toolchain request for the directory they were started in, so a workspace member and a
 * root-project workspace must both be composed as members rather than standalone.
 */
final class ToolchainConfigReaderWorkspaceTest {
    private final ToolchainConfigReader reader = new ToolchainConfigReader();

    @TempDir
    private Path tempDir;

    @Test
    void memberInheritsTheRootRequestAndTheSharedProjectRelease() throws IOException {
        Path member = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21

                [toolchain.java]
                distribution = "graalvm-community"
                """, "apps/api", """
                [project]
                name = "api"
                """);

        JavaToolchainRequest request = reader.readJava(member.resolve("zolt.toml")).orElseThrow();

        assertEquals("21", request.version());
        assertEquals(Optional.of(JavaDistribution.GRAALVM_COMMUNITY), request.distribution());
    }

    @Test
    void memberWithoutAnyToolchainRequestReportsNoRequestInsteadOfFailing() throws IOException {
        Path member = workspace("""
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["apps/platform"]
                include = ["apps/platform"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """, "apps/platform", """
                [project]
                name = "platform"
                """);

        assertTrue(reader.readJava(member.resolve("zolt.toml")).isEmpty());
        assertTrue(reader.readJavaTest(member.resolve("zolt.toml")).isEmpty());
    }

    /** Design §4.4/§6.9: a manifest declaring both [workspace] and [project] composes as `.`. */
    @Test
    void rootProjectWorkspaceComposesItsOwnToolchainRequest() throws IOException {
        Path root = tempDir.resolve("root-project");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = ["."]

                [workspace.project]
                group = "com.example"
                version = "1.4.0"
                java = 21

                [project]
                name = "platform-root"

                [toolchain.java]
                distribution = "temurin"

                [toolchain.java.test]
                version = 25
                """);

        JavaToolchainRequest main = reader.readJava(root.resolve("zolt.toml")).orElseThrow();
        JavaToolchainRequest test = reader.readJavaTest(root.resolve("zolt.toml")).orElseThrow();

        assertEquals("21", main.version());
        assertEquals(Optional.of(JavaDistribution.TEMURIN), main.distribution());
        assertEquals("25", test.version());
        assertEquals(Optional.of(root), reader.enclosingWorkspaceRoot(root));
    }

    @Test
    void virtualWorkspaceRootStillReportsItsSharedRequestAsAuthored() throws IOException {
        Path root = tempDir.resolve("virtual");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21

                [toolchain.java]
                distribution = "graalvm-community"
                """);

        JavaToolchainRequest request = reader.readJava(root.resolve("zolt.toml")).orElseThrow();

        assertEquals("21", request.version());
        assertEquals(Optional.of(JavaDistribution.GRAALVM_COMMUNITY), request.distribution());
    }

    private Path workspace(String rootSource, String memberPath, String memberSource)
            throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), rootSource);
        Path member = root.resolve(memberPath);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), memberSource);
        return member;
    }
}
