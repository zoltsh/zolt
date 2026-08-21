package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResolveServiceBetaFixtureMatrixTest extends ResolveServiceTestSupport {
    @Test
    void betaFixtureCombinesPomNormalizationPoliciesScopesAndStableLockfile() throws IOException {
        addBetaPlatform();
        addBetaServiceStarter();
        addArtifact("com.acme", "service-core", "1.1.0", simplePom("com.acme", "service-core", "1.1.0"));
        addArtifact("com.acme", "runtime-helper", "1.0.0", simplePom("com.acme", "runtime-helper", "1.0.0"));
        addArtifact("com.acme", "legacy-logging", "1.0.0", simplePom("com.acme", "legacy-logging", "1.0.0"));
        addArtifact("com.acme", "optional-helper", "1.0.0", simplePom("com.acme", "optional-helper", "1.0.0"));
        addArtifact("com.acme", "test-helper", "1.0.0", simplePom("com.acme", "test-helper", "1.0.0"));
        addArtifact("org.slf4j", "slf4j-simple", "2.0.17", simplePom("org.slf4j", "slf4j-simple", "2.0.17"));
        addArtifact("jakarta.servlet", "jakarta.servlet-api", "6.1.0", simplePom("jakarta.servlet", "jakarta.servlet-api", "6.1.0"));
        addArtifact("org.junit.jupiter", "junit-jupiter-api", "5.11.4", simplePom("org.junit.jupiter", "junit-jupiter-api", "5.11.4"));
        addJUnitConsoleArtifact("1.11.4");
        addArtifact("com.modern", "relocated-api", "2.0.0", simplePom("com.modern", "relocated-api", "2.0.0"));
        Path projectDir = tempDir.resolve("project-beta-fixture");
        Path cacheRoot = tempDir.resolve("cache-beta-fixture");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, betaConfig(), cacheRoot);
        String firstLockfile = Files.readString(result.lockfilePath());
        ResolveResult repeated = resolveService.resolve(projectDir, betaConfig(), cacheRoot);

        assertEquals(firstLockfile, Files.readString(repeated.lockfilePath()));
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertPackage(lockfile, "com.acme", "service-starter", "1.0.0", DependencyScope.COMPILE, true);
        assertPackage(lockfile, "com.acme", "service-core", "1.1.0", DependencyScope.COMPILE, false);
        assertPackage(lockfile, "com.acme", "runtime-helper", "1.0.0", DependencyScope.RUNTIME, false);
        assertPackage(lockfile, "org.slf4j", "slf4j-simple", "2.0.17", DependencyScope.RUNTIME, true);
        assertPackage(lockfile, "jakarta.servlet", "jakarta.servlet-api", "6.1.0", DependencyScope.PROVIDED, true);
        assertPackage(lockfile, "org.junit.jupiter", "junit-jupiter-api", "5.11.4", DependencyScope.TEST, true);
        assertPackage(lockfile, "org.junit.platform", "junit-platform-console", "1.11.4", DependencyScope.TEST, false);
        assertPackage(lockfile, "com.modern", "relocated-api", "2.0.0", DependencyScope.COMPILE, true);
        assertAbsent(lockfile, "com.acme", "beta-platform");
        assertAbsent(lockfile, "com.acme", "service-parent");
        assertAbsent(lockfile, "com.old", "relocated-api");
        assertAbsent(lockfile, "com.acme", "legacy-logging");
        assertAbsent(lockfile, "com.acme", "optional-helper");
        assertAbsent(lockfile, "com.acme", "test-helper");
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                "edge-exclusion".equals(effect.kind())
                        && effect.packageId().equals(new PackageId("com.acme", "legacy-logging"))
                        && effect.source().orElseThrow().equals("com.acme:service-starter:1.0.0")));
        assertTrue(packageFor(lockfile, "com.acme", "service-starter").policies().contains(
                "managed-version: com.acme:service-starter -> 1.0.0 from com.acme:beta-platform:1.0.0"));
        String packageSections = firstLockfile.substring(firstLockfile.indexOf("[[package]]"));
        assertOrder(packageSections, "id = \"com.acme:runtime-helper\"", "id = \"com.acme:service-core\"");
        assertOrder(packageSections, "id = \"com.acme:service-core\"", "id = \"com.acme:service-starter\"");
        assertOrder(packageSections, "id = \"com.modern:relocated-api\"", "id = \"jakarta.servlet:jakarta.servlet-api\"");
        assertEquals("""
                packages
                com.acme:runtime-helper:1.0.0 scope=runtime direct=false dependencies=[]
                com.acme:service-core:1.1.0 scope=compile direct=false dependencies=[]
                com.acme:service-starter:1.0.0 scope=compile direct=true dependencies=[com.acme:runtime-helper:1.0.0:jar:runtime, com.acme:service-core:1.1.0:jar:compile]
                com.modern:relocated-api:2.0.0 scope=compile direct=true dependencies=[]
                jakarta.servlet:jakarta.servlet-api:6.1.0 scope=provided direct=true dependencies=[]
                org.junit.jupiter:junit-jupiter-api:5.11.4 scope=test direct=true dependencies=[]
                org.junit.platform:junit-platform-console:1.11.4 scope=test direct=false dependencies=[]
                org.slf4j:slf4j-simple:2.0.17 scope=runtime direct=true dependencies=[]
                policies
                edge-exclusion com.acme:legacy-logging requested=1.0.0 source=com.acme:service-starter:1.0.0
                conflicts
                """, goldenSummary(lockfile));
    }

    private void addBetaPlatform() {
        addPom("com.acme", "beta-platform", "1.0.0", """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>beta-platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.acme</groupId>
                        <artifactId>service-starter</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-simple</artifactId>
                        <version>2.0.17</version>
                      </dependency>
                      <dependency>
                        <groupId>jakarta.servlet</groupId>
                        <artifactId>jakarta.servlet-api</artifactId>
                        <version>6.1.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter-api</artifactId>
                        <version>5.11.4</version>
                      </dependency>
                      <dependency>
                        <groupId>org.junit.platform</groupId>
                        <artifactId>junit-platform-console</artifactId>
                        <version>1.11.4</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
    }

    private void addBetaServiceStarter() {
        addPom("com.acme", "service-parent", "1.0.0", """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>service-parent</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.acme</groupId>
                        <artifactId>service-core</artifactId>
                        <version>1.1.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact("com.acme", "service-starter", "1.0.0", """
                <project>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>service-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>service-starter</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>service-core</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>runtime-helper</artifactId>
                      <version>1.0.0</version>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>jakarta.servlet</groupId>
                      <artifactId>jakarta.servlet-api</artifactId>
                      <version>6.1.0</version>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>test-helper</artifactId>
                      <version>1.0.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>optional-helper</artifactId>
                      <version>1.0.0</version>
                      <optional>true</optional>
                    </dependency>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>legacy-logging</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    private ProjectConfig betaConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [repositories]
                test = "%s"

                [platforms]
                "com.acme:beta-platform" = "1.0.0"

                [dependencies]
                "com.acme:service-starter" = { exclusions = [{ group = "com.acme", artifact = "legacy-logging" }] }
                "com.modern:relocated-api" = "2.0.0"

                [runtime.dependencies]
                "org.slf4j:slf4j-simple" = {}

                [provided.dependencies]
                "jakarta.servlet:jakarta.servlet-api" = {}

                [test.dependencies]
                "org.junit.jupiter:junit-jupiter-api" = {}
                """.formatted(baseUri));
    }

    private static void assertPackage(
            ZoltLockfile lockfile,
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean direct) {
        LockPackage lockPackage = packageFor(lockfile, groupId, artifactId);
        assertEquals(version, lockPackage.version());
        assertEquals(scope, lockPackage.scope());
        assertEquals(direct, lockPackage.direct());
    }

    private static LockPackage packageFor(ZoltLockfile lockfile, String groupId, String artifactId) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(groupId, artifactId)))
                .findFirst()
                .orElseThrow();
    }

    private static void assertAbsent(ZoltLockfile lockfile, String groupId, String artifactId) {
        assertFalse(lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().equals(new PackageId(groupId, artifactId))));
    }

    private static void assertOrder(String output, String first, String second) {
        assertTrue(output.indexOf(first) >= 0, first);
        assertTrue(output.indexOf(second) >= 0, second);
        assertTrue(output.indexOf(first) < output.indexOf(second));
    }

    private static String goldenSummary(ZoltLockfile lockfile) {
        StringBuilder summary = new StringBuilder();
        summary.append("packages\n");
        for (LockPackage lockPackage : lockfile.packages()) {
            summary.append(lockPackage.packageId())
                    .append(':')
                    .append(lockPackage.version())
                    .append(" scope=")
                    .append(lockPackage.scope().name().toLowerCase().replace('_', '-'))
                    .append(" direct=")
                    .append(lockPackage.direct())
                    .append(" dependencies=")
                    .append(lockPackage.dependencies())
                    .append('\n');
        }
        summary.append("policies\n");
        for (var effect : lockfile.policyEffects()) {
            summary.append(effect.kind())
                    .append(' ')
                    .append(effect.packageId())
                    .append(" requested=")
                    .append(effect.requestedVersion().orElse("<none>"))
                    .append(" source=")
                    .append(effect.source().orElse("<none>"))
                    .append('\n');
        }
        summary.append("conflicts\n");
        for (var conflict : lockfile.conflicts()) {
            summary.append(conflict.packageId())
                    .append(" selected=")
                    .append(conflict.selectedVersion())
                    .append(" requested=")
                    .append(conflict.requestedVersions())
                    .append(" reason=")
                    .append(conflict.reason())
                    .append('\n');
        }
        return summary.toString();
    }
}
