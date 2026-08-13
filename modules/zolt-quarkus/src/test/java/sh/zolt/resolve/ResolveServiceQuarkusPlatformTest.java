package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResolveServiceQuarkusPlatformTest extends ResolveServiceQuarkusTestSupport {
    @Test
    void quarkusMetadataParentFirstArtifactsEnterDeploymentClasspathWhenVersionIsManaged() {
        addPom("io.quarkus.platform", "quarkus-bom", "3.33.0", """
                <project>
                  <groupId>io.quarkus.platform</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>3.33.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.quarkus</groupId>
                        <artifactId>quarkus-rest</artifactId>
                        <version>3.33.0</version>
                      </dependency>
                      <dependency>
                        <groupId>io.quarkus</groupId>
                        <artifactId>quarkus-rest-deployment</artifactId>
                        <version>3.33.0</version>
                      </dependency>
                      <dependency>
                        <groupId>io.quarkus</groupId>
                        <artifactId>quarkus-bootstrap-maven-resolver</artifactId>
                        <version>3.33.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.jacoco</groupId>
                        <artifactId>org.jacoco.agent</artifactId>
                        <version>0.8.14</version>
                      </dependency>
                      <dependency>
                        <groupId>org.jacoco</groupId>
                        <artifactId>org.jacoco.agent</artifactId>
                        <version>0.8.14</version>
                        <classifier>runtime</classifier>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
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
                parent-first-artifacts=io.quarkus:quarkus-bootstrap-maven-resolver,org.jacoco:org.jacoco.agent:runtime
                """));
        addArtifact("io.quarkus", "quarkus-rest-deployment", "3.33.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-rest-deployment</artifactId>
                  <version>3.33.0</version>
                </project>
                """);
        addArtifact("io.quarkus", "quarkus-bootstrap-maven-resolver", "3.33.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-bootstrap-maven-resolver</artifactId>
                  <version>3.33.0</version>
                </project>
                """);
        addArtifact("org.jacoco", "org.jacoco.agent", "0.8.14", """
                <project>
                  <groupId>org.jacoco</groupId>
                  <artifactId>org.jacoco.agent</artifactId>
                  <version>0.8.14</version>
                </project>
                """);
        addClassifierJar("org.jacoco", "org.jacoco.agent", "0.8.14", "runtime", Map.of());
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                quarkusPlatformConfigWithDependencies(Map.of("io.quarkus:quarkus-rest", "")),
                cacheRoot);

        assertEquals(4, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-bootstrap-maven-resolver"))
                        && lockPackage.scope() == DependencyScope.QUARKUS_DEPLOYMENT));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("org.jacoco", "org.jacoco.agent"))
                        && lockPackage.scope() == DependencyScope.QUARKUS_DEPLOYMENT
                        && lockPackage.jar().orElseThrow().endsWith(
                                "/org.jacoco.agent-0.8.14-runtime.jar")));
    }

    @Test
    void quarkusPlatformPropertiesArtifactIsResolvedFromPlatformBom() {
        addPom("io.quarkus.platform", "quarkus-bom", "3.33.0", """
                <project>
                  <groupId>io.quarkus.platform</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>3.33.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.quarkus.platform</groupId>
                        <artifactId>quarkus-bom-quarkus-platform-properties</artifactId>
                        <version>3.33.0</version>
                        <type>properties</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        addArtifact(
                "io.quarkus.platform",
                "quarkus-bom-quarkus-platform-properties",
                "3.33.0",
                "properties",
                """
                platform.quarkus.native.builder-image=builder
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                quarkusPlatformConfigWithDependencies(Map.of()),
                cacheRoot);

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage properties = lockfile.packages().getFirst();
        assertEquals(
                new PackageId("io.quarkus.platform", "quarkus-bom-quarkus-platform-properties"),
                properties.packageId());
        assertEquals(DependencyScope.QUARKUS_DEPLOYMENT, properties.scope());
        assertTrue(properties.jar().isEmpty());
        assertEquals("properties", properties.artifactType().orElseThrow());
        assertTrue(properties.artifact().orElseThrow().endsWith(
                "/quarkus-bom-quarkus-platform-properties-3.33.0.properties"));
        assertEquals(List.of(), new ClasspathBuilder()
                .build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot))
                .quarkusDeployment()
                .entries());
    }

    @Test
    void quarkusDeploymentArtifactWithClassifierResolvesClassifierJarPath() {
        addArtifact("io.quarkus", "quarkus-custom", "1.0.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-custom</artifactId>
                  <version>1.0.0</version>
                </project>
                """, Map.of(
                "META-INF/quarkus-extension.properties",
                "deployment-artifact=io.quarkus:quarkus-custom-deployment:deployment:jar:1.0.0\n"));
        addPom("io.quarkus", "quarkus-custom-deployment", "1.0.0", """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-custom-deployment</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        addClassifierJar("io.quarkus", "quarkus-custom-deployment", "1.0.0", "deployment", Map.of());
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                quarkusConfigWithDependencies(Map.of("io.quarkus:quarkus-custom", "1.0.0")),
                cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("io.quarkus", "quarkus-custom-deployment"))
                        && lockPackage.scope() == DependencyScope.QUARKUS_DEPLOYMENT
                        && lockPackage.jar().orElseThrow().endsWith(
                                "/quarkus-custom-deployment-1.0.0-deployment.jar")));
    }
}
