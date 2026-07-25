package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberGraphIsolationTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void exclusionsRemainMemberQualifiedForConsumersAndSboms() throws IOException {
        addArtifact("com.example", "root", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>leaf</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "leaf", "1.0.0", pom("com.example", "leaf", "1.0.0"));
        workspace("""
                [workspace]
                name = "member-graphs"
                members = ["apps/api", "apps/worker", "apps/api-consumer", "apps/worker-consumer"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [api.dependencies]
                "com.example:root" = { version = "1.0.0", exclusions = [{ group = "com.example", artifact = "leaf" }] }
                """);
        member("apps/worker", "worker", """

                [api.dependencies]
                "com.example:root" = "1.0.0"
                """);
        member("apps/api-consumer", "api-consumer", """

                [dependencies]
                "com.acme:api" = { workspace = "apps/api" }
                """);
        member("apps/worker-consumer", "worker-consumer", """

                [dependencies]
                "com.acme:worker" = { workspace = "apps/worker" }
                """);

        Path cache = tempDir.resolve("cache");
        service.resolve(tempDir, cache, false, false);
        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        Workspace workspace = new WorkspaceDiscoveryService().discover(tempDir).orElseThrow();
        WorkspaceClasspathService classpaths = new WorkspaceClasspathService();

        assertLeafAbsent(classpaths.classpathsFor(
                workspace, lockfile, cache, "apps/api-consumer").compile().entries());
        assertLeafPresent(classpaths.classpathsFor(
                workspace, lockfile, cache, "apps/worker-consumer").compile().entries());
        assertLeafAbsent(projectedPackages(workspace, lockfile, "apps/api-consumer"));
        assertLeafPresent(projectedPackages(workspace, lockfile, "apps/worker-consumer"));
    }

    private static List<Path> projectedPackages(
            Workspace workspace,
            ZoltLockfile lockfile,
            String memberPath) {
        WorkspaceMember member = workspace.members().stream()
                .filter(candidate -> candidate.path().equals(memberPath))
                .findFirst()
                .orElseThrow();
        WorkspaceMemberPolicyResolver policies = new WorkspaceMemberPolicyResolver();
        return new WorkspaceMemberSbomLockProjection()
                .project(
                        member.path(),
                        policies.merge(workspace, member),
                        lockfile,
                        workspace,
                        policies)
                .packages()
                .stream()
                .flatMap(lockPackage -> lockPackage.jar().stream())
                .map(Path::of)
                .toList();
    }

    private static void assertLeafAbsent(List<Path> entries) {
        assertFalse(entries.stream().anyMatch(WorkspaceMemberGraphIsolationTest::isLeaf));
    }

    private static void assertLeafPresent(List<Path> entries) {
        assertTrue(entries.stream().anyMatch(WorkspaceMemberGraphIsolationTest::isLeaf));
    }

    private static boolean isLeaf(Path path) {
        return path.getFileName().toString().equals("leaf-1.0.0.jar");
    }
}
