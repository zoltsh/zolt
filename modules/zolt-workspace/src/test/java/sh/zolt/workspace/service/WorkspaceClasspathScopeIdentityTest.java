package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceClasspathScopeIdentityTest {
    private final WorkspaceClasspathService service = new WorkspaceClasspathService();

    @TempDir
    private Path tempDir;

    @Test
    void exportedCompileClosureDoesNotPromoteSameVariantRuntimeEdge() throws IOException {
        Workspace workspace = workspace();
        Path apiJar = tempDir.resolve("cache/com/example/api-lib/1.0.0/api-lib-1.0.0.jar");
        Path helperJar = tempDir.resolve("cache/com/example/helper/1.0.0/helper-1.0.0.jar");
        createFile(apiJar);
        createFile(helperJar);
        ZoltLockfile lockfile = WorkspaceContentAddressedLockTestSupport.migrate(tempDir.resolve("cache"), """
                version = 3

                [[package]]
                id = "com.example:api-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/api-lib/1.0.0/api-lib-1.0.0.jar"
                members = ["modules/core"]
                exportedBy = ["modules/core"]
                dependencies = ["com.example:helper:1.0.0:jar:runtime"]

                [[package]]
                id = "com.example:helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/helper/1.0.0/helper-1.0.0.jar"
                members = ["apps/worker"]
                dependencies = []

                [[package]]
                id = "com.example:helper"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/helper/1.0.0/helper-1.0.0.jar"
                members = ["modules/core"]
                dependencies = []
                """);
        apiJar = WorkspaceContentAddressedLockTestSupport.cachedArtifact(tempDir.resolve("cache"), apiJar);
        helperJar = WorkspaceContentAddressedLockTestSupport.cachedArtifact(tempDir.resolve("cache"), helperJar);

        ClasspathSet classpaths =
                service.classpathsFor(workspace, lockfile, tempDir.resolve("cache"), "apps/api");

        assertTrue(classpaths.compile().entries().contains(apiJar));
        assertFalse(classpaths.compile().entries().contains(helperJar));
        assertTrue(classpaths.runtime().entries().contains(helperJar));
    }

    private Workspace workspace() throws IOException {
        List<String> members = List.of("modules/core", "apps/api", "apps/worker");
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), "");
        for (String member : members) {
            Files.createDirectories(tempDir.resolve(member));
        }
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig("acme-platform", members, List.of(), Map.of(), Map.of()),
                members.stream()
                        .map(member -> new WorkspaceMember(member, tempDir.resolve(member), null))
                        .toList(),
                List.of(new WorkspaceProjectEdge(
                        "apps/api", "modules/core", "compile", "com.acme:core")),
                members);
    }

    private static void createFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
    }
}
