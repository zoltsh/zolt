package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ResolveServiceSelectedGraphMaterializationTest extends ResolveServiceTestSupport {
    private static final PackageId ENGINE = new PackageId("com.example", "engine");

    @Test
    void materializesSelectedRuntimeVersionUnderDevScope() {
        assertSelectedGraph(DependencyScope.RUNTIME, DependencyScope.DEV);
    }

    @Test
    void materializesSelectedCompileVersionUnderRuntimeScope() {
        assertSelectedGraph(DependencyScope.COMPILE, DependencyScope.RUNTIME);
    }

    @Test
    void materializesSelectedTestVersionUnderCompileScope() {
        assertSelectedGraph(DependencyScope.TEST, DependencyScope.COMPILE);
    }

    @Test
    void freshSameCoordinateChildVersionReplacesTentativeSelection() {
        addArtifact("com.example", "driver", "1.0.0", simplePom("driver", "1.0.0"));
        addArtifact("com.example", "driver", "2.0.0", simplePom("driver", "2.0.0"));
        addArtifact("com.example", "engine", "1.0.0", pomWithVersionedChild("1.0.0", "driver", "1.0.0"));
        addArtifact("com.example", "engine", "2.0.0", pomWithVersionedChild("2.0.0", "driver", "2.0.0"));
        addArtifact("com.example", "root-a", "1.0.0", rootPom("root-a", "1.0.0"));
        addArtifact("com.example", "root-b", "1.0.0", excludingRootPom("root-b", "2.0.0", "driver"));
        Path project = tempDir.resolve("same-child-coordinate");
        createDirectory(project);

        ResolveResult result = resolveService.resolve(
                project,
                new ZoltTomlParser().parse("""
                        [project]
                        name = "selected-graph"
                        version = "0.1.0"
                        group = "com.example"
                        java = "21"

                        [repositories]
                        test = "%s"

                        [dependencies]
                        "com.example:root-a" = "1.0.0"
                        "com.example:root-b" = "1.0.0"
                        """.formatted(baseUri)),
                tempDir.resolve("same-child-cache"));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage engine = packageById(lockfile, ENGINE);
        LockPackage driver = packageById(lockfile, new PackageId("com.example", "driver"));
        assertEquals("2.0.0", engine.version());
        assertEquals(List.of("com.example:driver:2.0.0:jar:compile"), engine.dependencies());
        assertEquals("2.0.0", driver.version());
    }

    @Test
    void fixedOverridesMaterializeTargetsWithoutRewritingFreshEdges() {
        addArtifact("com.example", "driver", "1.0.0", simplePom("driver", "1.0.0"));
        addArtifact("com.example", "driver", "2.0.0", simplePom("driver", "2.0.0"));
        addArtifact("com.example", "engine", "1.0.0", pomWithVersionedChild("1.0.0", "driver", "1.0.0"));
        addArtifact("com.example", "engine", "2.0.0", pomWithVersionedChild("2.0.0", "driver", "2.0.0"));
        addArtifact("com.example", "root-a", "1.0.0", rootPom("root-a", "1.0.0"));
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "selected-graph"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                test = "%s"

                [dependencies]
                "com.example:root-a" = "1.0.0"
                """.formatted(baseUri));
        ResolveOutput output = resolveService.resolveLockfile(
                config,
                tempDir.resolve("fixed-override-cache"),
                ResolveOptions.defaults().withVersionOverrides(java.util.Map.of(
                        new ResolutionVariant(ENGINE, sh.zolt.lockfile.LockArtifactVariant.defaultVariant()),
                        "2.0.0",
                        new ResolutionVariant(
                                new PackageId("com.example", "driver"),
                                sh.zolt.lockfile.LockArtifactVariant.defaultVariant()),
                        "2.0.0")));

        LockPackage engine = packageById(output.lockfile(), ENGINE);
        LockPackage driver = packageById(
                output.lockfile(), new PackageId("com.example", "driver"));
        assertEquals("2.0.0", engine.version());
        assertEquals(List.of("com.example:driver:2.0.0:jar:compile"), engine.dependencies());
        assertEquals("2.0.0", driver.version());
    }

    private void assertSelectedGraph(DependencyScope firstScope, DependencyScope secondScope) {
        addFixture();
        if (firstScope == DependencyScope.TEST || secondScope == DependencyScope.TEST) {
            addJUnitConsoleArtifact("1.11.4");
        }
        Path project = tempDir.resolve(firstScope.lockfileName() + "-" + secondScope.lockfileName());
        createDirectory(project);

        ResolveResult result = resolveService.resolve(
                project,
                config(firstScope, secondScope),
                tempDir.resolve("cache-" + firstScope.lockfileName() + "-" + secondScope.lockfileName()));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        List<LockPackage> engines = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(ENGINE))
                .toList();
        assertEquals(Set.of(firstScope, secondScope), engines.stream()
                .map(LockPackage::scope)
                .collect(java.util.stream.Collectors.toSet()));
        engines.forEach(engine -> {
            assertEquals("2.0.0", engine.version());
            assertEquals(
                    List.of("com.example:selected-child:1.0.0:jar:" + engine.scope().lockfileName()),
                    engine.dependencies());
        });
        assertFalse(lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().artifactId().equals("legacy-child")));
    }

    private void addFixture() {
        addArtifact("com.example", "legacy-child", "1.0.0", simplePom("legacy-child"));
        addArtifact("com.example", "selected-child", "1.0.0", simplePom("selected-child"));
        addArtifact("com.example", "engine", "1.0.0", pomWithChild("1.0.0", "legacy-child"));
        addArtifact("com.example", "engine", "2.0.0", pomWithChild("2.0.0", "selected-child"));
        addArtifact("com.example", "first-root", "1.0.0", rootPom("first-root", "1.0.0"));
        addArtifact("com.example", "second-root", "1.0.0", rootPom("second-root", "2.0.0"));
    }

    private ProjectConfig config(DependencyScope firstScope, DependencyScope secondScope) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "selected-graph"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                test = "%s"

                [%s]
                "com.example:first-root" = "1.0.0"

                [%s]
                "com.example:second-root" = "1.0.0"
                """.formatted(
                baseUri,
                section(firstScope),
                section(secondScope)));
    }

    private static String section(DependencyScope scope) {
        return scope == DependencyScope.COMPILE
                ? "dependencies"
                : scope.lockfileName() + ".dependencies";
    }

    private static String simplePom(String artifactId) {
        return simplePom(artifactId, "1.0.0");
    }

    private static String simplePom(String artifactId, String version) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(artifactId, version);
    }

    private static String pomWithChild(String version, String child) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>engine</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>%s</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, child);
    }

    private static String rootPom(String artifactId, String engineVersion) {
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
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(artifactId, engineVersion);
    }

    private static String pomWithVersionedChild(
            String version,
            String child,
            String childVersion) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>engine</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, child, childVersion);
    }

    private static String excludingRootPom(
            String artifactId,
            String engineVersion,
            String excludedArtifact) {
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
                      <exclusions>
                        <exclusion>
                          <groupId>com.example</groupId>
                          <artifactId>%s</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(artifactId, engineVersion, excludedArtifact);
    }

    private static LockPackage packageById(
            ZoltLockfile lockfile,
            PackageId packageId) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }
}
