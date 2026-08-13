package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WorkspaceResolveServiceLockedFailureTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void lockedWorkspaceResolveFailsWhenRootLockfileWouldChange() throws IOException {
        addArtifact("com.example", "extra", "1.0.0", pom("com.example", "extra", "1.0.0"));
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String existing = Files.readString(first.lockfilePath());
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                "com.example:extra" = "1.0.0"
                """);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(tempDir, tempDir.resolve("cache"), true, false));

        assertTrue(exception.getMessage().contains("Workspace zolt.lock is out of date"));
        assertEquals(existing, Files.readString(first.lockfilePath()));
    }

    @Test
    void lockedWorkspaceResolveFailsWhenRepositoryInputChangesWithoutGraphChange() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String existing = Files.readString(first.lockfilePath());
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s./"
                """.formatted(baseUri));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(tempDir, tempDir.resolve("cache"), true, false));

        assertTrue(existing.contains("projectResolutionFingerprint = \"sha256:"));
        assertTrue(exception.getMessage().contains("Workspace zolt.lock is out of date"));
        assertTrue(exception.getMessage().contains("Changed inputs: repositories."));
        assertEquals(existing, Files.readString(first.lockfilePath()));
    }

    @Test
    void lockedWorkspaceResolveFailsWhenMemberPlatformVersionRefEdgeChangesWithoutConcreteVersionChange()
            throws IOException {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        platformVersionRefMember("platform-one");
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String existing = Files.readString(first.lockfilePath());
        platformVersionRefMember("platform-two");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(tempDir, tempDir.resolve("cache"), true, false));

        assertTrue(existing.contains("aliasFingerprint = \"sha256:"));
        assertTrue(exception.getMessage().contains("Workspace zolt.lock is out of date"));
        assertEquals(existing, Files.readString(first.lockfilePath()));
    }

    @Test
    void lockedWorkspaceResolveFailsWhenMemberVersionAliasTableChangesWithoutGraphChange()
            throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        unusedAliasMember("unused-one");
        ResolveResult first = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        String existing = Files.readString(first.lockfilePath());
        unusedAliasMember("unused-two");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(tempDir, tempDir.resolve("cache"), true, false));

        assertTrue(existing.contains("aliasFingerprint = \"sha256:"));
        assertTrue(exception.getMessage().contains("Workspace zolt.lock is out of date"));
        assertEquals(existing, Files.readString(first.lockfilePath()));
    }
}
