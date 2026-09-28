package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveResult;

final class WorkspaceGroovyToolIsolationTest extends WorkspaceResolveServiceTestSupport {
    private static final PackageId GROOVY =
            new PackageId("org.apache.groovy", "groovy");
    private static final PackageId GROOVY_HELPER =
            new PackageId("org.apache.groovy", "groovy-helper");

    @Test
    void preservesApplicationAndCompilerVersionsInIndependentScopes() throws IOException {
        addArtifact(
                "org.apache.groovy",
                "groovy",
                "4.0.22",
                pom("org.apache.groovy", "groovy", "4.0.22"));
        addArtifact("org.apache.groovy", "groovy", "4.0.23", """
                <project>
                  <groupId>org.apache.groovy</groupId>
                  <artifactId>groovy</artifactId>
                  <version>4.0.23</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.groovy</groupId>
                      <artifactId>groovy-helper</artifactId>
                      <version>4.0.23</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact(
                "org.apache.groovy",
                "groovy-helper",
                "4.0.23",
                pom("org.apache.groovy", "groovy-helper", "4.0.23"));
        workspace("""
                [workspace]
                name = "groovy-tool-isolation"

                [workspace.members]
                include = ["apps/api", "apps/worker"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [toolchain.groovy]
                version = "4.0.23"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "org.apache.groovy:groovy" = "4.0.22"
                """);
        member("apps/worker", "worker", "");

        ResolveResult result =
                service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage application = packageEntry(
                lockfile, GROOVY, DependencyScope.COMPILE, "4.0.22");
        LockPackage compiler = packageEntry(
                lockfile, GROOVY, DependencyScope.TOOL_GROOVY, "4.0.23");
        LockPackage helper = packageEntry(
                lockfile, GROOVY_HELPER, DependencyScope.TOOL_GROOVY, "4.0.23");

        assertTrue(application.direct());
        assertTrue(compiler.direct());
        assertFalse(helper.direct());
        assertEquals(
                List.of("org.apache.groovy:groovy-helper:4.0.23:jar:tool-groovy"),
                compiler.dependencies());
        assertTrue(helper.dependencies().isEmpty());
        assertFalse(lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().equals(GROOVY_HELPER)
                        && lockPackage.scope() != DependencyScope.TOOL_GROOVY));
        assertFalse(lockfile.conflicts().stream()
                .anyMatch(conflict -> conflict.packageId().equals(GROOVY)));
    }

    private static LockPackage packageEntry(
            ZoltLockfile lockfile,
            PackageId packageId,
            DependencyScope scope,
            String version) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .filter(lockPackage -> lockPackage.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing " + packageId + ":" + version + " in " + scope.lockfileName()));
    }
}
