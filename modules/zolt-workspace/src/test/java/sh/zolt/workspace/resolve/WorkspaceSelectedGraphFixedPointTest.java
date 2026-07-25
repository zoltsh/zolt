package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceSelectedGraphFixedPointTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void workspaceMediationUsesOnlyTheFinalSelectedGraph() throws IOException {
        addArtifact("com.example", "driver", "1.0.0", pom("com.example", "driver", "1.0.0"));
        addArtifact("com.example", "driver", "3.0.0", pom("com.example", "driver", "3.0.0"));
        addArtifact("com.example", "engine", "1.0.0", enginePom("1.0.0", "3.0.0"));
        addArtifact("com.example", "engine", "2.0.0", enginePom("2.0.0", "1.0.0"));
        addArtifact("com.example", "root-a", "1.0.0", rootPom("root-a", "1.0.0", false));
        addArtifact("com.example", "root-b", "1.0.0", rootPom("root-b", "2.0.0", false));
        workspace("""
                [workspace]
                name = "selected-graph-no-resurrection"
                members = ["apps/a", "apps/b"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/a", "a", """

                [dependencies]
                "com.example:root-a" = "1.0.0"
                """);
        member("apps/b", "b", """

                [dependencies]
                "com.example:root-b" = "1.0.0"
                """);

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        assertEquals("2.0.0", packageById(lockfile, "engine").version());
        assertEquals("1.0.0", packageById(lockfile, "driver").version());
        var driverConflict = lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(
                        new PackageId("com.example", "driver")))
                .findFirst()
                .orElseThrow();
        assertEquals(
                sh.zolt.dependency.ConflictSelectionReason.SELECTED_GRAPH,
                driverConflict.reason());
        assertFalse(driverConflict.reason()
                == sh.zolt.dependency.ConflictSelectionReason.NEWEST_VERSION);
    }

    @Test
    void recomputesMediationAfterFreshSameCoordinateChildRequest() throws IOException {
        addArtifact("com.example", "driver", "1.0.0", pom("com.example", "driver", "1.0.0"));
        addArtifact("com.example", "driver", "2.0.0", pom("com.example", "driver", "2.0.0"));
        addArtifact("com.example", "engine", "1.0.0", enginePom("1.0.0", "1.0.0"));
        addArtifact("com.example", "engine", "2.0.0", enginePom("2.0.0", "2.0.0"));
        addArtifact("com.example", "root-a", "1.0.0", rootPom("root-a", "1.0.0", false));
        addArtifact("com.example", "root-b", "1.0.0", rootPom("root-b", "2.0.0", true));
        workspace("""
                [workspace]
                name = "selected-graph-fixed-point"
                members = ["apps/a", "apps/b"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/a", "a", """

                [dependencies]
                "com.example:root-a" = "1.0.0"
                """);
        member("apps/b", "b", """

                [dependencies]
                "com.example:root-b" = "1.0.0"
                """);

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        LockPackage engine = packageById(lockfile, "engine");
        LockPackage driver = packageById(lockfile, "driver");
        assertEquals("2.0.0", engine.version());
        assertEquals(
                List.of("com.example:driver:2.0.0:jar:compile"),
                engine.dependencies(),
                Files.readString(tempDir.resolve("zolt.lock")));
        assertEquals("2.0.0", driver.version());
    }

    @Test
    void preservesInitialMemberConflictRequestsAndAttribution() throws IOException {
        addArtifact("com.example", "library", "1.0.0", pom("com.example", "library", "1.0.0"));
        addArtifact("com.example", "library", "2.0.0", pom("com.example", "library", "2.0.0"));
        addArtifact("com.example", "library", "3.0.0", pom("com.example", "library", "3.0.0"));
        addArtifact("com.example", "root", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>library</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        workspace("""
                [workspace]
                name = "conflict-provenance"
                members = ["apps/a", "apps/b"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/a", "a", """

                [dependencies]
                "com.example:library" = "1.0.0"
                "com.example:root" = "1.0.0"
                """);
        member("apps/b", "b", """

                [dependencies]
                "com.example:library" = "3.0.0"
                """);

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        var conflict = lockfile.conflicts().stream()
                .filter(candidate -> candidate.packageId().equals(
                        new PackageId("com.example", "library")))
                .findFirst()
                .orElseThrow();
        assertEquals("3.0.0", conflict.selectedVersion());
        assertEquals(
                Set.of("1.0.0", "2.0.0", "3.0.0"),
                Set.copyOf(conflict.requestedVersions()));
        assertTrue(conflict.members().containsAll(
                List.of("apps/a", "apps/b")));
    }

    private static LockPackage packageById(
            ZoltLockfile lockfile,
            String artifactId) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId()
                        .equals(new PackageId("com.example", artifactId)))
                .filter(lockPackage -> lockPackage.scope() == DependencyScope.COMPILE)
                .findFirst()
                .orElseThrow();
    }

    private static String enginePom(
            String version,
            String driverVersion) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>engine</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>driver</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, driverVersion);
    }

    private static String rootPom(
            String artifactId,
            String engineVersion,
            boolean excludeDriver) {
        String exclusions = excludeDriver
                ? """
                      <exclusions>
                        <exclusion>
                          <groupId>com.example</groupId>
                          <artifactId>driver</artifactId>
                        </exclusion>
                      </exclusions>
                  """
                : "";
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>engine</artifactId>
                      <version>%s</version>
                %s
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(artifactId, engineVersion, exclusions);
    }
}
