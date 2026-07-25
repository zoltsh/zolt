package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.resolve.ResolveException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WorkspaceSameVersionSubstitutionRejectionTest
        extends WorkspaceResolveServiceTestSupport {
    @Test
    void rejectsLocalAndReleasedBytesAtTheSameGraphIdentityBeforeWritingLock()
            throws IOException {
        addArtifact(
                "com.acme",
                "core",
                "0.1.0",
                pom("com.acme", "core", "0.1.0"));
        workspace("""
                [workspace]
                name = "same-version-substitution"
                members = ["modules/core", "apps/app", "apps/worker"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", "");
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("apps/worker", "worker", """

                [dependencies]
                "com.acme:core" = "0.1.0"
                """);
        Path localClass = tempDir.resolve(
                "modules/core/target/classes/com/acme/core/LocalOnly.class");
        Files.createDirectories(localClass.getParent());
        Files.writeString(
                localClass,
                "deliberately different from repository jar bytes");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(
                        tempDir,
                        tempDir.resolve("cache"),
                        false,
                        false));

        assertTrue(exception.getMessage().contains(
                "com.acme:core:0.1.0:jar:compile"));
        assertTrue(exception.getMessage().contains(
                "workspace member `modules/core`"));
        assertTrue(exception.getMessage().contains(
                "repository source"));
        assertTrue(exception.getMessage().contains(
                "explicit for every affected consumer"));
        assertFalse(Files.exists(tempDir.resolve("zolt.lock")));
    }
}
