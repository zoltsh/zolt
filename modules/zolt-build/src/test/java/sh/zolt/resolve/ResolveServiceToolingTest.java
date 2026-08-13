package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServiceToolingTest extends ResolveServiceTestSupport {
    @Test
    void testDependenciesAddJUnitPlatformConsoleRunnerTooling() {
        addArtifact("org.junit.platform", "junit-platform-console", "1.11.4", """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-console</artifactId>
                  <version>1.11.4</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-launcher</artifactId>
                      <version>1.11.4</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("org.junit.platform", "junit-platform-launcher", "1.11.4", """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-launcher</artifactId>
                  <version>1.11.4</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithTestDependencies(Map.of("com.example:app", "1.0.0")),
                cacheRoot);

        assertEquals(4, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))
                        && lockPackage.scope() == DependencyScope.TEST
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.junit.platform", "junit-platform-console"))
                        && lockPackage.version().equals("1.11.4")
                        && lockPackage.scope() == DependencyScope.TEST
                        && !lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.junit.platform", "junit-platform-launcher"))
                        && lockPackage.version().equals("1.11.4")
                        && lockPackage.scope() == DependencyScope.TEST
                        && !lockPackage.direct()));
    }

    @Test
    void testRunnerToolingUsesProjectManagedConsoleVersion() {
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
                      <dependency>
                        <groupId>org.junit.platform</groupId>
                        <artifactId>junit-platform-console</artifactId>
                        <version>1.10.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addJUnitConsoleArtifact("1.10.2");
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, testPlatformConfig(), cacheRoot);

        assertEquals(3, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.junit.platform", "junit-platform-console"))
                        && lockPackage.version().equals("1.10.2")
                        && lockPackage.scope() == DependencyScope.TEST
                        && !lockPackage.direct()));
    }

    @Test
    void explicitJUnitPlatformConsoleDependencyStaysDirect() {
        addArtifact("org.junit.platform", "junit-platform-console-standalone", "1.11.4", """
                <project>
                  <groupId>org.junit.platform</groupId>
                  <artifactId>junit-platform-console-standalone</artifactId>
                  <version>1.11.4</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithTestDependencies(Map.of("org.junit.platform:junit-platform-console-standalone", "1.11.4")),
                cacheRoot);

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.junit.platform", "junit-platform-console-standalone"))
                        && lockPackage.scope() == DependencyScope.TEST
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.junit.platform", "junit-platform-console"))));
    }

    @Test
    void directTestDependencyRelocationLocksRelocatedArtifact() {
        addPom("io.quarkus", "quarkus-junit5", "3.33.2", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-junit5</artifactId>
                  <version>3.33.2</version>
                  <distributionManagement>
                    <relocation>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-junit</artifactId>
                      <version>${project.version}</version>
                      <message>Use io.quarkus:quarkus-junit instead.</message>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        addArtifact("io.quarkus", "quarkus-junit", "3.33.2", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-junit</artifactId>
                  <version>3.33.2</version>
                </project>
                """);
        addJUnitConsoleArtifact("1.11.4");
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithTestDependencies(Map.of("io.quarkus:quarkus-junit5", "3.33.2")),
                cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-junit"))
                        && lockPackage.version().equals("3.33.2")
                        && lockPackage.scope() == DependencyScope.TEST
                        && lockPackage.direct()
                        && lockPackage.jar().orElseThrow().startsWith("blobs/v2/sha256/")
                        && lockPackage.jar().orElseThrow().endsWith("/quarkus-junit-3.33.2.jar")));
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-junit5"))));
    }
}
