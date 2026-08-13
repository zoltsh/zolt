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

final class ResolveServiceQuarkusSuccessTest extends ResolveServiceQuarkusTestSupport {
    @Test
    void quarkusRuntimeExtensionAddsDeploymentArtifactScope() {
        addArtifact("io.quarkus", "quarkus-rest", "3.33.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-rest</artifactId>
                  <version>3.33.0</version>
                </project>
                """, Map.of(
                "META-INF/quarkus-extension.properties",
                """
                deployment-artifact=io.quarkus:quarkus-rest-deployment:3.33.0
                provides-capabilities=io.quarkus.rest
                """));
        addArtifact("io.quarkus", "quarkus-rest-deployment", "3.33.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-rest-deployment</artifactId>
                  <version>3.33.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-core-deployment</artifactId>
                      <version>3.33.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("io.quarkus", "quarkus-core-deployment", "3.33.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-core-deployment</artifactId>
                  <version>3.33.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                quarkusConfigWithDependencies(Map.of("io.quarkus:quarkus-rest", "3.33.0")),
                cacheRoot);

        assertEquals(3, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-rest"))
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-rest-deployment"))
                        && lockPackage.scope() == DependencyScope.QUARKUS_DEPLOYMENT
                        && !lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-core-deployment"))
                        && lockPackage.scope() == DependencyScope.QUARKUS_DEPLOYMENT
                        && !lockPackage.direct()));

        ClasspathSet classpaths = new ClasspathBuilder().build(
                LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
        Path restJar = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-rest")))
                .findFirst()
                .orElseThrow()
                .jarPath()
                .orElseThrow()
                .resolveWithin(cacheRoot);
        assertEquals(List.of(restJar), classpaths.compile().entries());
        assertEquals(List.of(restJar), classpaths.runtime().entries());
        assertTrue(classpaths.runtime().entries().stream()
                .noneMatch(path -> path.toString().contains("deployment")));
        assertEquals(List.of(), classpaths.processor().entries());
        assertEquals(List.of(), classpaths.testProcessor().entries());
    }

    @Test
    void quarkusRuntimeExtensionDoesNotAddDeploymentArtifactScopeUnlessEnabled() {
        addArtifact("io.quarkus", "quarkus-rest", "3.33.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-rest</artifactId>
                  <version>3.33.0</version>
                </project>
                """, Map.of(
                "META-INF/quarkus-extension.properties",
                "deployment-artifact=io.quarkus:quarkus-rest-deployment:3.33.0\n"));
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithDependencies(Map.of("io.quarkus:quarkus-rest", "3.33.0")),
                cacheRoot);

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.scope() == DependencyScope.QUARKUS_DEPLOYMENT));
    }
}
